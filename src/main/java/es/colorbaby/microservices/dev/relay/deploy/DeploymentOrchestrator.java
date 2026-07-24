package es.colorbaby.microservices.dev.relay.deploy;

import es.colorbaby.microservices.dev.relay.config.DeploymentProperties;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.harbor.client.HarborClient;
import es.colorbaby.microservices.dev.relay.jenkins.client.JenkinsClient;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lleva cada despliegue por su recorrido, un paso por barrido: espera a que el build salga de la
 * cola, espera a que termine, resuelve en Harbor la VERSION que produjo, lanza el job de deploy y
 * espera a que termine. Al acabar, avisa al {@link DeploymentCoordinator}.
 *
 * <p>Lee los despliegues en curso de la base de datos, así que <b>tras un reinicio los retoma</b>
 * donde estaban en vez de perderlos (que era el problema serio del diseño anterior en memoria).
 *
 * <p>Best-effort y acotado: un tope de sondeos común evita seguir un despliegue colgado para
 * siempre. Si un job falla y hay LLM, se adjunta un diagnóstico de la consola.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeploymentOrchestrator {

  private static final String DIAGNOSE_SYSTEM_PROMPT =
      "Eres Sixai. Te doy la consola (la cola) de un job de Jenkins que ha fallado. Resume en "
      + "pocas frases, en español, la causa más probable del fallo y qué habría que revisar. Sé "
      + "concreto y no inventes: si la consola no basta para saberlo, dilo.";

  private final DeploymentProperties properties;
  private final DeploymentRunRepository runs;
  private final DeploymentCoordinator coordinator;
  private final JenkinsClient jenkinsClient;
  private final HarborClient harborClient;
  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final DeployDiagnosisService deployDiagnosis;

  /** Barrido periódico. El intervalo es {@code maestro.deploy.poll-interval-ms}. */
  @Scheduled(fixedDelayString = "${maestro.deploy.poll-interval-ms:30000}")
  public void sweep() {
    if (!properties.isEnabled()) {
      return;
    }
    final List<DeploymentRun> pending = runs.findByStatus(DeploymentStatus.RUNNING);
    for (final DeploymentRun run : pending) {
      try {
        advance(run);
      } catch (RuntimeException e) {
        log.warn("Fallo avanzando el despliegue de {} ({}): {}",
            run.getRepo(), run.getIssueKey(), e.getMessage());
        runs.save(run);
      }
    }
  }

  private void advance(final DeploymentRun run) {
    if (run.incrementAttempts() > properties.getMaxAttempts()) {
      finishFailure(run, "se agotó la espera siguiendo el despliegue");
      return;
    }
    switch (run.getStage()) {
      case BUILD_QUEUED -> resolveQueued(run, DeploymentRun.Stage.BUILD_RUNNING);
      case BUILD_RUNNING -> onBuildRunning(run);
      case DEPLOY_QUEUED -> resolveQueued(run, DeploymentRun.Stage.DEPLOY_RUNNING);
      case DEPLOY_RUNNING -> onDeployRunning(run);
      default -> log.warn("Estado no contemplado: {}", run.getStage());
    }
    if (run.getStatus() == DeploymentStatus.RUNNING) {
      runs.save(run);
    }
  }

  // La cola no es un build todavía: mientras no haya "executable", simplemente se espera.
  private void resolveQueued(final DeploymentRun run, final DeploymentRun.Stage next) {
    final Optional<Integer> number = jenkinsClient.resolveQueuedBuild(run.getQueueUrl());
    if (number.isEmpty()) {
      return;
    }
    if (next == DeploymentRun.Stage.BUILD_RUNNING) {
      run.setBuildNumber(number.get());
    } else {
      run.setDeployBuildNumber(number.get());
    }
    run.setStage(next);
    log.info("{} de {} arrancó como build #{}", next, run.getRepo(), number.get());
  }

  private void onBuildRunning(final DeploymentRun run) {
    final Optional<JenkinsClient.Build> build =
        jenkinsClient.getBuild(run.getBuildJob(), run.getBuildNumber());
    if (build.isEmpty() || !build.get().finished()) {
      return;
    }
    if (!build.get().success()) {
      finishFailure(run, "el build de " + run.getBranch() + " terminó en "
          + build.get().result() + diagnose(run.getBuildJob(), run.getBuildNumber()));
      return;
    }
    // El build publica la imagen en Harbor: su última etiqueta es la VERSION a desplegar.
    final Optional<String> version = harborClient.latestTag(run.getService());
    if (version.isEmpty()) {
      finishFailure(run, "el build fue bien pero no encontré la imagen de "
          + run.getService() + " en Harbor");
      return;
    }
    run.setImageVersion(version.get());
    try {
      final String queueUrl = jenkinsClient.triggerBuild(run.getDeployJob(), Map.of(
          "SERVICE", run.getService(),
          "VERSION", version.get(),
          "ENVIRONMENT", run.getEnvironment(),
          "ACTION", "INSTALL"));
      run.setQueueUrl(queueUrl);
      run.setStage(DeploymentRun.Stage.DEPLOY_QUEUED);
      log.info("Desplegando {}:{} en {} para {}", run.getService(), version.get(),
          run.getEnvironment(), run.getIssueKey());
    } catch (RuntimeException e) {
      finishFailure(run, "no se pudo lanzar el despliegue: " + e.getMessage());
    }
  }

  private void onDeployRunning(final DeploymentRun run) {
    final Optional<JenkinsClient.Build> build =
        jenkinsClient.getBuild(run.getDeployJob(), run.getDeployBuildNumber());
    if (build.isEmpty() || !build.get().finished()) {
      return;
    }
    if (build.get().success()) {
      log.info("{}:{} desplegado en {} para {}", run.getService(), run.getImageVersion(),
          run.getEnvironment(), run.getIssueKey());
      finishSuccess(run);
      return;
    }
    // En el fallo de DESPLIEGUE se mira más allá de la consola: el gate de Harbor y el propio
    // contenedor en la máquina destino, que es donde está de verdad la causa.
    final String reason = "el despliegue a " + run.getEnvironment() + " terminó en "
        + build.get().result() + diagnose(run.getDeployJob(), run.getDeployBuildNumber());
    finishFailure(run, reason + deployDiagnosis.diagnose(run, reason));
  }

  private void finishSuccess(final DeploymentRun run) {
    run.setStatus(DeploymentStatus.SUCCEEDED);
    runs.save(run);
    coordinator.onRunFinished(run);
  }

  private void finishFailure(final DeploymentRun run, final String reason) {
    run.setStatus(DeploymentStatus.FAILED);
    // El diagnóstico del LLM puede ser largo; se recorta a lo que cabe en la columna.
    run.setFailureReason(reason == null || reason.length() <= DeploymentRun.FAILURE_REASON_MAX
        ? reason : reason.substring(0, DeploymentRun.FAILURE_REASON_MAX));
    runs.save(run);
    log.error("Despliegue de {} para {} fallido: {}", run.getRepo(), run.getIssueKey(), reason);
    coordinator.onRunFinished(run);
  }

  private String diagnose(final String jobPath, final int buildNumber) {
    if (!properties.isDiagnoseOnFailure() || !llmProperties.isEnabled()) {
      return "";
    }
    try {
      final String console = jenkinsClient.getConsoleLog(jobPath, buildNumber);
      final String tail = tail(console, properties.getConsoleMaxChars());
      final String diagnosis = llmClient.complete(
          LlmRequest.of(DIAGNOSE_SYSTEM_PROMPT, tail, LlmRoles.DIAGNOSE, jobPath));
      return diagnosis == null || diagnosis.isBlank()
          ? "" : "\nDiagnóstico de Sixai:\n" + diagnosis;
    } catch (RuntimeException e) {
      log.warn("No se pudo diagnosticar el job {}: {}", jobPath, e.getMessage());
      return "";
    }
  }

  private static String tail(final String value, final int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(value.length() - max);
  }
}

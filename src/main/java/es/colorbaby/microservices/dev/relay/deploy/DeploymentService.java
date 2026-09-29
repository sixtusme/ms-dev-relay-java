package es.colorbaby.microservices.dev.relay.deploy;

import es.colorbaby.microservices.dev.relay.config.DeploymentProperties;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Evidence;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceKind;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceSource;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseOutcome;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Recommendation;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskLifecycle;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskPhase;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Verdict;
import es.colorbaby.microservices.dev.relay.jenkins.client.JenkinsClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Arranca el despliegue de los repos de una tarea: lanza el job de BUILD de cada uno y persiste el
 * recorrido para que {@link DeploymentOrchestrator} lo lleve hasta el final (build → versión en
 * Harbor → deploy), sobreviviendo a reinicios.
 *
 * <p>El lote se crea con los despliegues que <b>realmente</b> arrancaron. Es importante: si se
 * apuntaran de forma optimista todos los repos pedidos, uno no desplegable (o un job que Jenkins
 * rechaza) dejaría el lote esperando un desenlace que nunca llega, y la tarea se quedaría callada.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeploymentService {

  /** Qué arrancó y qué se quedó fuera, para poder contarlo en la tarea. */
  public record StartResult(int started, List<String> skipped) {
  }

  private final JenkinsClient jenkinsClient;
  private final DeploymentBatchRepository batches;
  private final DeploymentRunRepository runs;
  private final DeploymentProperties properties;
  private final TaskLifecycle taskLifecycle;

  /**
   * Lanza el build de cada repo hacia un entorno y deja el lote persistido.
   *
   * <p>Es el único choque de PRE y PROD (aprobación y promoción llegan aquí), así que aquí van
   * también el {@code check} del ciclo de vida y el {@code accept} de su arranque: así no se le
   * escapa a un camino nuevo que alguien añada mañana, igual que la comprobación de lote vivo.
   *
   * @param branchByRepo repo de GitHub → rama a compilar (develop en PRE, main/master en PROD)
   * @return cuántos arrancaron y cuáles se quedaron fuera
   */
  @Transactional
  public StartResult startBatch(final String issueKey, final DeploymentPhase phase,
      final String reporterAccountId, final Map<String, String> branchByRepo) {
    final TaskPhase target = targetPhase(phase);
    if (!properties.isEnabled()) {
      return new StartResult(0, List.copyOf(branchByRepo.keySet()));
    }
    if (properties.isDryRun()) {
      branchByRepo.forEach((repo, branch) ->
          log.info("[DRY-RUN] Compilaría {}@{} y desplegaría en {}", repo, branch, phase));
      taskLifecycle.accept(issueKey, PhaseOutcome.of(target, Recommendation.SKIPPED,
          Evidence.of(EvidenceKind.REASON, "dry-run: no se despliega", EvidenceSource.SYSTEM)));
      return new StartResult(0, List.of());
    }
    // Una tarea no puede tener dos despliegues vivos a la vez: serían dos builds del mismo repo
    // pisándose y dos imágenes distintas peleando por la misma máquina. La comprobación va AQUÍ,
    // que es por donde pasan tanto la aprobación a PRE como la promoción a PROD, y no en cada
    // llamante: así no se la salta un camino nuevo que alguien añada mañana.
    if (!batches.findByIssueKeyAndStatus(issueKey, DeploymentStatus.RUNNING).isEmpty()) {
      log.warn("{} ya tiene un despliegue en curso; no se arranca otro ({})", issueKey, phase);
      return new StartResult(0, List.of());
    }
    final Verdict verdict = taskLifecycle.check(issueKey, target);
    if (verdict.denied()) {
      log.warn("Ciclo de vida deniega el despliegue a {} para {}: {}",
          phase, issueKey, verdict.reason());
      return new StartResult(0, List.copyOf(branchByRepo.keySet()));
    }

    final DeploymentBatch batch =
        batches.save(new DeploymentBatch(issueKey, phase, reporterAccountId));
    final List<String> skipped = new ArrayList<>();
    int started = 0;

    for (final Map.Entry<String, String> entry : branchByRepo.entrySet()) {
      if (startOne(batch, issueKey, entry.getKey(), entry.getValue(), phase)) {
        started++;
      } else {
        skipped.add(entry.getKey());
      }
    }

    if (started == 0) {
      batch.setStatus(DeploymentStatus.FAILED);
      batches.save(batch);
      log.warn("Ningún despliegue arrancó para {} ({}); no queda lote esperando", issueKey, phase);
      taskLifecycle.accept(issueKey, PhaseOutcome.of(target, Recommendation.FAIL,
          Evidence.of(EvidenceKind.REASON, "ningún repo pudo arrancar el despliegue",
              EvidenceSource.JENKINS)));
    } else {
      taskLifecycle.accept(issueKey, PhaseOutcome.of(target, Recommendation.STARTED,
          Evidence.of(EvidenceKind.REASON, started + " de " + branchByRepo.size()
              + " repo(s) arrancados" + (skipped.isEmpty() ? "" : "; fuera: " + skipped),
              EvidenceSource.JENKINS)));
    }
    return new StartResult(started, skipped);
  }

  private static TaskPhase targetPhase(final DeploymentPhase phase) {
    return phase == DeploymentPhase.PRE ? TaskPhase.DEPLOY_PRE : TaskPhase.PROMOTION;
  }

  private boolean startOne(final DeploymentBatch batch, final String issueKey, final String repoName,
      final String branch, final DeploymentPhase phase) {
    final Optional<DeploymentProperties.Repo> config = properties.findRepo(repoName);
    if (config.isEmpty()) {
      log.info("{} no es desplegable por el pipeline (sin entrada en maestro.deploy.repos)",
          repoName);
      return false;
    }
    final DeploymentProperties.Repo repo = config.get();
    final Optional<String> buildJob = properties.buildJob(repo.getPipeline());
    if (buildJob.isEmpty()) {
      log.warn("Sin job de build para el pipeline '{}' de {}", repo.getPipeline(), repoName);
      return false;
    }

    final String environment = phase == DeploymentPhase.PROD
        ? repo.getProdEnvironment() : repo.getPreEnvironment();
    final String deployJob = phase == DeploymentPhase.PROD && repo.getProdJob() != null
        && !repo.getProdJob().isBlank() ? repo.getProdJob() : properties.getDeployJob();

    try {
      final String queueUrl = jenkinsClient.triggerBuild(buildJob.get(),
          Map.of("REPOSITORY", repoName, "BRANCH", branch));
      final DeploymentRun run = new DeploymentRun(batch.getId(), issueKey, repoName,
          repo.serviceName(), branch, environment, phase, buildJob.get(), deployJob);
      run.setQueueUrl(queueUrl);
      runs.save(run);
      log.info("Build de {}@{} encolado para {} ({}): {}", repoName, branch, issueKey, phase,
          queueUrl);
      return true;
    } catch (RuntimeException e) {
      log.error("No se pudo lanzar el build de {}@{}: {}", repoName, branch, e.getMessage());
      return false;
    }
  }
}

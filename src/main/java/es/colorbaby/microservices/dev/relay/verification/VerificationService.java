package es.colorbaby.microservices.dev.relay.verification;

import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.config.DeploymentProperties;
import es.colorbaby.microservices.dev.relay.config.VerificationProperties;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentStatus;
import es.colorbaby.microservices.dev.relay.jenkins.client.JenkinsClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Manda compilar la rama de una PR para saber si el código del coder se sostiene, antes de que
 * nadie lo apruebe.
 *
 * <p>Usa el <b>mismo pipeline</b> que el despliegue, pero con {@code FAST_SNAPSHOT_BUILD=true}, y
 * ahí está el truco: sin ese flag el pipeline commitea la versión, la empuja a la rama y crea el tag
 * {@code release/X.Y.Z}: efectos que no se quieren para una rama que a lo mejor nunca se mergea. Con
 * el flag compila, pasa los gates de seguridad y no toca el historial.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationService {

  private final JenkinsClient jenkinsClient;
  private final VerificationRunRepository runs;
  private final TaskRecorder taskRecorder;
  private final DeploymentProperties deploymentProperties;
  private final VerificationProperties properties;

  /**
   * Lanza la verificación de una PR recién abierta. Best-effort: si no se puede, la PR se queda sin
   * veredicto pero el trabajo sigue.
   *
   * @param issueKey tarea a la que pertenece
   * @param repo     repositorio de GitHub
   * @param branch   rama de la PR, que es lo que se compila
   * @param prNumber número de la PR, para poder comentar el resultado en ella
   */
  public void verify(final String issueKey, final String repo, final String branch,
      final int prNumber) {
    if (!properties.isEnabled()) {
      return;
    }
    final Optional<String> buildJob = buildJobFor(repo);
    if (buildJob.isEmpty()) {
      return;
    }
    if (properties.isDryRun()) {
      log.info("[DRY-RUN] Verificaría {}@{} con {}", repo, branch, buildJob.get());
      return;
    }
    try {
      final String queueUrl = jenkinsClient.triggerBuild(buildJob.get(), Map.of(
          "REPOSITORY", repo,
          "BRANCH", branch,
          // La clave de todo: compila sin crear tag de release ni empujar a la rama.
          "FAST_SNAPSHOT_BUILD", "true"));
      final VerificationRun run = new VerificationRun(issueKey, repo, branch, prNumber,
          buildJob.get());
      run.setQueueUrl(queueUrl);
      runs.save(run);
      taskRecorder.record(issueKey, TaskEventType.VERIFY_STARTED, "sixai",
          repo + " #" + prNumber + " (" + branch + ")");
      log.info("Verificación de {}@{} encolada para {}: {}", repo, branch, issueKey, queueUrl);
    } catch (RuntimeException e) {
      log.error("No se pudo verificar {}@{}: {}", repo, branch, e.getMessage());
    }
  }

  /** Verificaciones de una tarea, de la más reciente a la más antigua. */
  public List<VerificationRun> forIssue(final String issueKey) {
    return runs.findByIssueKeyOrderByIdDesc(issueKey);
  }

  /**
   * Si hay alguna verificación fallida que deba frenar la aprobación. Solo manda si está configurado
   * para bloquear; si no, el veredicto es informativo y decide la persona.
   */
  public boolean blocks(final String issueKey) {
    if (!properties.isEnabled() || !properties.isBlockApproval()) {
      return false;
    }
    return runs.findByIssueKeyOrderByIdDesc(issueKey).stream()
        .anyMatch(run -> run.getStatus() == DeploymentStatus.FAILED);
  }

  /** El job que compila este repo, reutilizando el mapeo del despliegue para no duplicarlo. */
  private Optional<String> buildJobFor(final String repo) {
    final Optional<DeploymentProperties.Repo> config = deploymentProperties.findRepo(repo);
    if (config.isEmpty()) {
      log.debug("{} no se compila por el pipeline; no hay nada que verificar", repo);
      return Optional.empty();
    }
    final Optional<String> buildJob = deploymentProperties.buildJob(config.get().getPipeline());
    if (buildJob.isEmpty()) {
      log.warn("Sin job de build para el pipeline '{}' de {}", config.get().getPipeline(), repo);
    }
    return buildJob;
  }
}

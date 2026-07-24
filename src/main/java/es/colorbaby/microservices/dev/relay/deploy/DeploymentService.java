package es.colorbaby.microservices.dev.relay.deploy;

import es.colorbaby.microservices.dev.relay.config.DeploymentProperties;
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

    public boolean any() {
      return started > 0;
    }
  }

  private final JenkinsClient jenkinsClient;
  private final DeploymentBatchRepository batches;
  private final DeploymentRunRepository runs;
  private final DeploymentProperties properties;

  /**
   * Lanza el build de cada repo hacia un entorno y deja el lote persistido.
   *
   * @param branchByRepo repo de GitHub → rama a compilar (develop en PRE, main/master en PROD)
   * @return cuántos arrancaron y cuáles se quedaron fuera
   */
  @Transactional
  public StartResult startBatch(final String issueKey, final DeploymentPhase phase,
      final String reporterAccountId, final Map<String, String> branchByRepo) {
    if (!properties.isEnabled()) {
      return new StartResult(0, List.copyOf(branchByRepo.keySet()));
    }
    if (properties.isDryRun()) {
      branchByRepo.forEach((repo, branch) ->
          log.info("[DRY-RUN] Compilaría {}@{} y desplegaría en {}", repo, branch, phase));
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
    }
    return new StartResult(started, skipped);
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

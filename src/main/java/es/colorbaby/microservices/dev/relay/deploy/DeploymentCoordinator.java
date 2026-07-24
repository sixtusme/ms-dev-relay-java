package es.colorbaby.microservices.dev.relay.deploy;

import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.activity.TaskRun;
import es.colorbaby.microservices.dev.relay.config.ApprovalProperties;
import es.colorbaby.microservices.dev.relay.correction.CorrectionService;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierra un lote cuando sus despliegues terminan. Una tarea puede tocar varios repos: solo cuando
 * TODOS acaban se decide el desenlace, y si alguno falló, el lote falla.
 *
 * <p>No lleva cuentas en memoria: el estado se <b>deriva</b> de los despliegues persistidos, así que
 * un reinicio a mitad no pierde el hilo. El lote se marca cerrado antes de avisar, para no comentar
 * dos veces.
 *
 * <p>El desenlace depende de la fase: en <b>PRE</b> se reasigna al informador y la tarea pasa a
 * TEST; en <b>PROD</b> solo se avisa — la tarea <b>se queda en TEST</b> para que el cliente lo
 * verifique en producción.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeploymentCoordinator {

  private final JiraClient jiraClient;
  private final DeploymentBatchRepository batches;
  private final DeploymentRunRepository runs;
  private final TaskRecorder taskRecorder;
  private final CorrectionService correctionService;
  private final ApprovalProperties approvalProperties;

  /** Un despliegue terminó: si era el último del lote, cierra el lote y avisa. */
  @Transactional
  public void onRunFinished(final DeploymentRun run) {
    final DeploymentBatch batch = batches.findById(run.getBatchId()).orElse(null);
    if (batch == null || batch.getStatus() != DeploymentStatus.RUNNING) {
      return;
    }
    final List<DeploymentRun> siblings = runs.findByBatchId(batch.getId());
    if (siblings.stream().anyMatch(r -> r.getStatus() == DeploymentStatus.RUNNING)) {
      return;
    }

    final List<DeploymentRun> failed = siblings.stream()
        .filter(r -> r.getStatus() == DeploymentStatus.FAILED)
        .toList();
    batch.setStatus(failed.isEmpty() ? DeploymentStatus.SUCCEEDED : DeploymentStatus.FAILED);
    batches.save(batch);

    if (failed.isEmpty()) {
      complete(batch);
    } else {
      abort(batch, failed);
    }
  }

  private void complete(final DeploymentBatch batch) {
    if (batch.getPhase() == DeploymentPhase.PROD) {
      jiraClient.addComment(batch.getIssueKey(), "🚀 Desplegado en PRODUCCIÓN. La tarea se queda "
          + "en " + approvalProperties.getTestStatus() + " para que lo verifiques.");
      log.info("{} desplegada en PROD", batch.getIssueKey());
      taskRecorder.record(batch.getIssueKey(), TaskEventType.DEPLOYED_PROD, "sixai", null);
      taskRecorder.phase(batch.getIssueKey(), "PROD");
      // Producción es el final del recorrido de sixai: aquí se cierra y se mide lo que tardó.
      taskRecorder.finish(batch.getIssueKey(), TaskRun.DONE);
      return;
    }
    if (batch.getReporterAccountId() != null && !batch.getReporterAccountId().isBlank()) {
      try {
        jiraClient.assignIssue(batch.getIssueKey(), batch.getReporterAccountId());
      } catch (RuntimeException e) {
        log.warn("No se pudo reasignar {} al informador: {}", batch.getIssueKey(), e.getMessage());
      }
    }
    try {
      jiraClient.transitionIssueByStatusName(batch.getIssueKey(),
          approvalProperties.getTestStatus());
    } catch (RuntimeException e) {
      log.warn("No se pudo pasar {} a {}: {}", batch.getIssueKey(),
          approvalProperties.getTestStatus(), e.getMessage());
    }
    jiraClient.addComment(batch.getIssueKey(), "✅ Desplegado en PRE, listo para revisar.");
    log.info("{} desplegada en PRE y movida a {}", batch.getIssueKey(),
        approvalProperties.getTestStatus());
    taskRecorder.record(batch.getIssueKey(), TaskEventType.DEPLOYED_PRE, "sixai", null);
    taskRecorder.record(batch.getIssueKey(), TaskEventType.MOVED_TO_TEST, "sixai",
        "reasignada al informador");
    // No se cierra la tarea: sigue viva a la espera de que el cliente la valide o pida cambios.
    taskRecorder.phase(batch.getIssueKey(), approvalProperties.getTestStatus());
  }

  private void abort(final DeploymentBatch batch, final List<DeploymentRun> failed) {
    final String detail = failed.stream()
        .map(r -> "- " + r.getRepo() + ": "
            + (r.getFailureReason() == null ? "sin detalle" : r.getFailureReason()))
        .collect(Collectors.joining("\n"));
    jiraClient.addComment(batch.getIssueKey(), "⚠️ El despliegue a " + batch.getPhase()
        + " falló:\n" + detail);
    log.error("Lote {} de {} fallido", batch.getPhase(), batch.getIssueKey());
    taskRecorder.record(batch.getIssueKey(), TaskEventType.FAILED, "sixai",
        "despliegue a " + batch.getPhase() + " fallido");

    // Solo se auto-corrige lo que falla camino de PRE. Un fallo en PRODUCCIÓN no se toca solo:
    // ahí la decisión es de una persona.
    if (batch.getPhase() == DeploymentPhase.PRE) {
      correctionService.triggeredByFailure(batch.getIssueKey(), detail);
    }
  }
}

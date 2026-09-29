package es.colorbaby.microservices.dev.relay.panel.monitor;

import es.colorbaby.microservices.dev.relay.activity.TaskEvent;
import es.colorbaby.microservices.dev.relay.activity.TaskEventRepository;
import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRun;
import es.colorbaby.microservices.dev.relay.activity.TaskRunRepository;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceEntry;
import es.colorbaby.microservices.dev.relay.control.lifecycle.LifecycleSnapshot;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseHistoryEntry;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseStatus;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskLifecycle;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskPhase;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentRun;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentRunRepository;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Responde a "¿qué está haciendo sixai ahora mismo?".
 *
 * <p>Lo interesante es cómo se calcula la <b>etapa</b>: no basta con la fase del ciclo de vida. Un
 * despliegue en curso es más preciso que la fase que lo arrancó (no es lo mismo "aprobado" que
 * "compilando develop" o "desplegando en PRE"), así que si hay un despliegue vivo, manda él. Si
 * no, se traduce la fase actual de {@link TaskLifecycle#snapshot}.
 */
@Component
@RequiredArgsConstructor
public class TaskMonitorService {

  private final TaskRunRepository tasks;
  private final TaskEventRepository events;
  private final DeploymentRunRepository deployments;
  private final TaskLifecycle taskLifecycle;

  /** Tareas que sixai tiene entre manos, de la más reciente a la más antigua. */
  @Transactional(readOnly = true)
  public List<ActiveTaskDto> active() {
    return tasks.findByStatusOrderByStartedAtDesc(TaskRun.RUNNING).stream()
        .map(this::toActive)
        .toList();
  }

  /** Todo lo que se sabe de una tarea, para seguir su avance en detalle. */
  @Transactional(readOnly = true)
  public Optional<TaskDetailDto> detail(final String issueKey) {
    return tasks.findByIssueKeyOrderByStartedAtDesc(issueKey).stream().findFirst().map(task -> {
      final List<TaskEvent> timeline = events.findByIssueKeyOrderByOccurredAtAsc(issueKey);
      final Optional<LifecycleSnapshot> snapshot = taskLifecycle.snapshot(issueKey);
      final Stage stage = stageOf(task, snapshot);

      final List<TaskDetailDto.TaskEventDto> eventDtos = timeline.stream()
          .map(event -> new TaskDetailDto.TaskEventDto(
              event.getType().name(),
              event.getActor(),
              event.getDetail(),
              event.getOccurredAt().toString()))
          .toList();

      final List<TaskDetailDto.TaskDeploymentDto> deploymentDtos =
          deployments.findByIssueKeyOrderByIdAsc(issueKey).stream()
          .map(run -> new TaskDetailDto.TaskDeploymentDto(
              run.getRepo(),
              run.getEnvironment(),
              run.getPhase().name(),
              run.getStage().name(),
              run.getStatus().name(),
              run.getImageVersion()))
          .toList();

      final List<TaskDetailDto.TaskPhaseDto> phaseDtos = snapshot
          .map(LifecycleSnapshot::history)
          .orElse(List.of())
          .stream()
          .map(TaskMonitorService::toPhaseDto)
          .toList();

      return new TaskDetailDto(task.getIssueKey(), task.getTitle(), task.getEpic(),
          task.getStatus(), stage.label(), stage.key(), task.getRequestedByName(),
          task.getStartedAt().toString(), task.getDurationMs(), eventDtos, deploymentDtos,
          phaseDtos);
    });
  }

  private ActiveTaskDto toActive(final TaskRun task) {
    final List<TaskEvent> timeline = events.findByIssueKeyOrderByOccurredAtAsc(task.getIssueKey());
    final Stage stage = stageOf(task, taskLifecycle.snapshot(task.getIssueKey()));
    final int prs = (int) timeline.stream()
        .filter(event -> event.getType() == TaskEventType.PR_OPENED)
        .count();
    return new ActiveTaskDto(
        task.getIssueKey(),
        task.getTitle() == null ? task.getIssueKey() : task.getTitle(),
        stage.label(), stage.key(), stage.detail(),
        task.getStartedAt().toString(),
        Duration.between(task.getStartedAt(), Instant.now()).toMillis(),
        prs);
  }

  /**
   * Un despliegue vivo describe la etapa mejor que la fase que lo arrancó (no es lo mismo
   * "aprobado" que "compilando develop" o "desplegando en PRE"); si no hay ninguno, se traduce la
   * fase del ciclo de vida.
   */
  private Stage stageOf(final TaskRun task, final Optional<LifecycleSnapshot> snapshot) {
    final Optional<DeploymentRun> running =
        deployments.findByIssueKeyOrderByIdAsc(task.getIssueKey()).stream()
            .filter(run -> run.getStatus() == DeploymentStatus.RUNNING)
            .findFirst();
    if (running.isPresent()) {
      return fromDeployment(running.get());
    }
    return snapshot.map(TaskMonitorService::fromSnapshot)
        .orElse(new Stage("Arrancando", "STARTING", null));
  }

  private static TaskDetailDto.TaskPhaseDto toPhaseDto(final PhaseHistoryEntry entry) {
    return new TaskDetailDto.TaskPhaseDto(entry.phase().name(), entry.iteration(),
        entry.status().name(), entry.decidedBy(), entry.startedAt().toString(),
        entry.finishedAt() == null ? null : entry.finishedAt().toString(),
        entry.evidence().stream().map(TaskMonitorService::toEvidenceDto).toList());
  }

  private static TaskDetailDto.TaskPhaseEvidenceDto toEvidenceDto(final EvidenceEntry evidence) {
    return new TaskDetailDto.TaskPhaseEvidenceDto(evidence.repo(),
        evidence.recommendation().name(), evidence.kind().name(), evidence.detail(),
        evidence.url(), evidence.actor(),
        evidence.reasonCode() == null ? null : evidence.reasonCode().name(),
        evidence.source() == null ? null : evidence.source().name(),
        evidence.recordedAt().toString());
  }

  private static Stage fromDeployment(final DeploymentRun run) {
    final String where = run.getRepo() + " → " + run.getEnvironment();
    return switch (run.getStage()) {
      case BUILD_QUEUED -> new Stage("Build en cola", "BUILD_QUEUED", where);
      case BUILD_RUNNING -> new Stage("Compilando", "BUILD_RUNNING", where);
      case DEPLOY_QUEUED -> new Stage("Despliegue en cola", "DEPLOY_QUEUED", where);
      case DEPLOY_RUNNING -> new Stage("Desplegando", "DEPLOY_RUNNING", where);
    };
  }

  /** Tabla de mapeo fase/estado → etiqueta/stageKey de la spec §7. Las stageKey no cambian. */
  private static Stage fromSnapshot(final LifecycleSnapshot snapshot) {
    final TaskPhase phase = snapshot.phase();
    if (phase == null) {
      return new Stage("Arrancando", "STARTING", null);
    }
    final boolean failed = snapshot.status() == PhaseStatus.FAILED;
    final String detail = detailOf(snapshot.current());
    return switch (phase) {
      case INTAKE -> new Stage("Detectada", "DETECTED", detail);
      case REPO_SELECTION -> new Stage("Analizando la tarea", "IN_PROGRESS", detail);
      case PLAN -> new Stage("Planificando", "IN_PROGRESS", detail);
      case IMPLEMENTATION -> failed
          ? new Stage("Sin código generado", "FAILED", detail)
          : snapshot.iteration() > 0
              ? new Stage("Corrigiendo", "CORRECTION", detail)
              : new Stage("Escribiendo código", "CODE_GENERATED", detail);
      case VERIFICATION -> failed
          ? new Stage("No compila", "VERIFY_FAILED", detail)
          : new Stage("Comprobando que compila", "VERIFYING", detail);
      case APPROVAL -> new Stage("Esperando aprobación", "AWAITING_APPROVAL", detail);
      case DEPLOY_PRE -> failed
          ? new Stage("Con fallos", "FAILED", detail)
          : new Stage("Mergeada a develop", "MERGED", detail);
      case CLIENT_TEST -> new Stage("En pruebas del cliente", "TEST", detail);
      case PROMOTION -> failed
          ? new Stage("Con fallos", "FAILED", detail)
          : new Stage("Promocionando a producción", "PROMOTING", detail);
      case DONE -> new Stage("Desplegada en producción", "DEPLOYED_PROD", detail);
      case ESCALATED -> new Stage("Necesita una persona", "GAVE_UP", detail);
      case FAILED -> new Stage("Con fallos", "FAILED", detail);
      case CANCELLED -> new Stage("Cancelada", "CANCELLED", detail);
    };
  }

  /** El texto más reciente de la fase actual, para que el panel siga teniendo algo que mostrar. */
  private static String detailOf(final PhaseHistoryEntry current) {
    if (current == null || current.evidence().isEmpty()) {
      return null;
    }
    return current.evidence().get(current.evidence().size() - 1).detail();
  }

  /** Etapa ya traducida a algo que una persona entiende de un vistazo. */
  private record Stage(String label, String key, String detail) {
  }
}

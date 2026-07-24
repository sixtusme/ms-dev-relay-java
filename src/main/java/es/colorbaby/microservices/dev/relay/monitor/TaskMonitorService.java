package es.colorbaby.microservices.dev.relay.monitor;

import es.colorbaby.microservices.dev.relay.activity.TaskEvent;
import es.colorbaby.microservices.dev.relay.activity.TaskEventRepository;
import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRun;
import es.colorbaby.microservices.dev.relay.activity.TaskRunRepository;
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
 * <p>Lo interesante es cómo se calcula la <b>etapa</b>: no basta con el último evento. Un despliegue
 * en curso es más preciso que el hito que lo arrancó (no es lo mismo "aprobado" que "compilando
 * develop" o "desplegando en PRE"), así que si hay un despliegue vivo, manda él. Si no, se traduce
 * el último hito de la línea de tiempo.
 */
@Component
@RequiredArgsConstructor
public class TaskMonitorService {

  private final TaskRunRepository tasks;
  private final TaskEventRepository events;
  private final DeploymentRunRepository deployments;

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
      final Stage stage = stageOf(task, timeline);

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

      return new TaskDetailDto(task.getIssueKey(), task.getTitle(), task.getEpic(),
          task.getStatus(), stage.label(), stage.key(), task.getRequestedByName(),
          task.getStartedAt().toString(), task.getDurationMs(), eventDtos, deploymentDtos);
    });
  }

  private ActiveTaskDto toActive(final TaskRun task) {
    final List<TaskEvent> timeline = events.findByIssueKeyOrderByOccurredAtAsc(task.getIssueKey());
    final Stage stage = stageOf(task, timeline);
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

  /** Un despliegue vivo describe la etapa mejor que el hito que lo arrancó. */
  private Stage stageOf(final TaskRun task, final List<TaskEvent> timeline) {
    final Optional<DeploymentRun> running =
        deployments.findByIssueKeyOrderByIdAsc(task.getIssueKey()).stream()
            .filter(run -> run.getStatus() == DeploymentStatus.RUNNING)
            .findFirst();
    if (running.isPresent()) {
      return fromDeployment(running.get());
    }
    if (timeline.isEmpty()) {
      return new Stage("Arrancando", "STARTING", null);
    }
    return fromEvent(timeline.get(timeline.size() - 1));
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

  private static Stage fromEvent(final TaskEvent event) {
    final String detail = event.getDetail();
    return switch (event.getType()) {
      case DETECTED -> new Stage("Detectada", "DETECTED", detail);
      case IN_PROGRESS -> new Stage("Analizando la tarea", "IN_PROGRESS", detail);
      case CODE_GENERATED -> new Stage("Escribiendo código", "CODE_GENERATED", detail);
      case PR_OPENED -> new Stage("Esperando aprobación", "AWAITING_APPROVAL", detail);
      case APPROVED -> new Stage("Aprobada", "APPROVED", detail);
      case MERGED -> new Stage("Mergeada a develop", "MERGED", detail);
      case BUILD_STARTED -> new Stage("Compilando", "BUILD_RUNNING", detail);
      case BUILD_OK -> new Stage("Build correcto", "BUILD_OK", detail);
      case BUILD_FAILED -> new Stage("Build fallido", "FAILED", detail);
      case DEPLOYED_PRE -> new Stage("Desplegada en PRE", "DEPLOYED_PRE", detail);
      case MOVED_TO_TEST -> new Stage("En pruebas del cliente", "TEST", detail);
      case COMMAND_RECEIVED -> new Stage("Atendiendo una orden", "COMMAND", detail);
      case CORRECTION_REQUESTED -> new Stage("Corrección pedida", "CORRECTION", detail);
      case CORRECTION_STARTED -> new Stage("Corrigiendo", "CORRECTION", detail);
      case PROMOTED -> new Stage("Promocionando a producción", "PROMOTING", detail);
      case DEPLOYED_PROD -> new Stage("Desplegada en producción", "DEPLOYED_PROD", detail);
      case GAVE_UP -> new Stage("Necesita una persona", "GAVE_UP", detail);
      case FAILED -> new Stage("Con fallos", "FAILED", detail);
    };
  }

  /** Etapa ya traducida a algo que una persona entiende de un vistazo. */
  private record Stage(String label, String key, String detail) {
  }
}

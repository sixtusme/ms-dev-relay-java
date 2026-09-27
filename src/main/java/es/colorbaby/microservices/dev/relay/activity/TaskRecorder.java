package es.colorbaby.microservices.dev.relay.activity;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deja constancia de lo que hace sixai: abre la tarea y apunta cada hito. Es la memoria de la
 * aplicación — sin ella no se puede responder qué se hizo ni quién lo pidió. El cierre de la tarea
 * (con cuánto tardó) lo decide el ciclo de vida ({@code TaskLifecycle}), no este componente.
 *
 * <p><b>Todo es best-effort:</b> registrar nunca puede tumbar el trabajo real. Si falla la base de
 * datos, se loguea y el flujo sigue; es preferible perder una traza que dejar una tarea a medias.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRecorder {

  private final TaskRunRepository tasks;
  private final TaskEventRepository events;

  /**
   * Abre una tarea (o devuelve la que ya estuviera viva, para no duplicar si se reprocesa).
   *
   * @return la tarea abierta, o vacío si no se pudo registrar
   */
  @Transactional
  public Optional<TaskRun> start(final String issueKey, final String requestedByAccountId,
      final String requestedByName, final String triggerSource) {
    try {
      final Optional<TaskRun> live = current(issueKey);
      if (live.isPresent()) {
        return live;
      }
      final TaskRun task = tasks.save(
          new TaskRun(issueKey, requestedByAccountId, requestedByName, triggerSource));
      record(issueKey, TaskEventType.DETECTED, requestedByName, null);
      return Optional.of(task);
    } catch (RuntimeException e) {
      log.warn("No se pudo abrir la tarea {} en el registro: {}", issueKey, e.getMessage());
      return Optional.empty();
    }
  }

  /** Completa los datos de la tarea que solo se conocen tras leerla de Jira. */
  @Transactional
  public void describe(final String issueKey, final String title, final String epic,
      final String systemName) {
    update(issueKey, task -> {
      task.setTitle(title);
      task.setEpic(epic);
      task.setSystemName(systemName);
    });
  }

  /** Añade un hito a la línea de tiempo. */
  @Transactional
  public void record(final String issueKey, final TaskEventType type, final String actor,
      final String detail) {
    try {
      final Long taskRunId = current(issueKey).map(TaskRun::getId).orElse(null);
      events.save(new TaskEvent(taskRunId, issueKey, type, actor,
          truncate(detail, TaskEvent.DETAIL_MAX)));
    } catch (RuntimeException e) {
      log.warn("No se pudo registrar el evento {} de {}: {}", type, issueKey, e.getMessage());
    }
  }

  /** Tarea viva de una issue, si la hay. */
  @Transactional(readOnly = true)
  public Optional<TaskRun> current(final String issueKey) {
    return tasks.findFirstByIssueKeyAndStatusOrderByStartedAtDesc(issueKey, TaskRun.RUNNING);
  }

  private void update(final String issueKey, final java.util.function.Consumer<TaskRun> change) {
    try {
      current(issueKey).ifPresent(task -> {
        change.accept(task);
        tasks.save(task);
      });
    } catch (RuntimeException e) {
      log.warn("No se pudo actualizar la tarea {}: {}", issueKey, e.getMessage());
    }
  }

  private static String truncate(final String value, final int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}

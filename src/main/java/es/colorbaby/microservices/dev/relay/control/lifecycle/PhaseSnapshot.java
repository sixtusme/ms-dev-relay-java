package es.colorbaby.microservices.dev.relay.control.lifecycle;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Foto del estado de una tarea para evaluar las reglas: la fase actual y la última pasada por
 * cada fase.
 *
 * @param current fase actual (la última abierta), o null si la tarea no tiene ninguna registrada
 */
public record PhaseSnapshot(TaskPhaseRun current, Map<TaskPhase, TaskPhaseRun> latest) {

  /** @param rows pasadas de la tarea en orden de apertura */
  public static PhaseSnapshot of(final List<TaskPhaseRun> rows) {
    final Map<TaskPhase, TaskPhaseRun> latest = new EnumMap<>(TaskPhase.class);
    rows.forEach(row -> latest.put(row.getPhase(), row));
    final TaskPhaseRun current = rows.isEmpty() ? null : rows.get(rows.size() - 1);
    return new PhaseSnapshot(current, Collections.unmodifiableMap(latest));
  }

  /** Última pasada por una fase, o null si nunca se entró en ella. */
  public TaskPhaseRun latest(final TaskPhase phase) {
    return latest.get(phase);
  }
}

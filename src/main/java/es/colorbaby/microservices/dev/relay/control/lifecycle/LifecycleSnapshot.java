package es.colorbaby.microservices.dev.relay.control.lifecycle;

import java.util.List;

/**
 * Todo lo que se sabe del recorrido de una tarea por el ciclo de vida: dónde está ahora y cómo
 * llegó. Lo devuelve {@link TaskLifecycle#snapshot(String)}; lo consumen el panel y
 * {@code /sixai STATUS}.
 *
 * @param phase     fase actual (la de la última pasada), o null si la tarea no llegó a registrarse
 * @param status    estado de esa fase, o null en las mismas condiciones que {@code phase}
 * @param iteration presupuesto de correcciones consumido ({@code task_run.remediation_iterations})
 * @param history   cada pasada por una fase, en el orden en que se abrieron
 */
public record LifecycleSnapshot(String issueKey, TaskPhase phase, PhaseStatus status,
    int iteration, List<PhaseHistoryEntry> history) {

  /** La pasada actual (la última del historial), o null si no hay ninguna. */
  public PhaseHistoryEntry current() {
    return history.isEmpty() ? null : history.get(history.size() - 1);
  }
}

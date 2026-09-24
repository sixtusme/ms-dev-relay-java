package es.colorbaby.microservices.dev.relay.control.lifecycle;

/**
 * Estado de una fase. El estado final ({@code PASSED}, {@code FAILED}, {@code SKIPPED}) es el gate:
 * junto con quién lo decidió y la evidencia, dice por qué la tarea pudo (o no) seguir.
 */
public enum PhaseStatus {
  PENDING,
  IN_PROGRESS,
  /** Reservado para las preguntas pendientes (fase 2): en este paso nadie lo produce. */
  BLOCKED,
  PASSED,
  FAILED,
  SKIPPED;

  public boolean isClosed() {
    return this == PASSED || this == FAILED || this == SKIPPED;
  }

  /** Si la tarea puede pasar a la fase siguiente. */
  public boolean letsAdvance() {
    return this == PASSED || this == SKIPPED;
  }
}

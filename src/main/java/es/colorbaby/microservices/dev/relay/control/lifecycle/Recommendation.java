package es.colorbaby.microservices.dev.relay.control.lifecycle;

/** Lo que un servicio recomienda para su fase al terminar su parte. Decide el lifecycle. */
public enum Recommendation {
  /** Ha arrancado algo que terminará más tarde (un build encolado, un despliegue). */
  STARTED,
  PASS,
  FAIL,
  SKIPPED,
  /** Reservado para las preguntas pendientes (fase 2). */
  BLOCKED;

  public PhaseStatus toStatus() {
    return switch (this) {
      case STARTED -> PhaseStatus.IN_PROGRESS;
      case PASS -> PhaseStatus.PASSED;
      case FAIL -> PhaseStatus.FAILED;
      case SKIPPED -> PhaseStatus.SKIPPED;
      case BLOCKED -> PhaseStatus.BLOCKED;
    };
  }

  /** Si es un veredicto final (sirve para cerrar una fase que se agrega por repos). */
  public boolean isVerdict() {
    return this == PASS || this == FAIL || this == SKIPPED;
  }
}

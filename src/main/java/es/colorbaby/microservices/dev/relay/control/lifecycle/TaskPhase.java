package es.colorbaby.microservices.dev.relay.control.lifecycle;

/**
 * Fases de la vida de una tarea en sixai, en el orden en que se recorren. Las cuatro últimas son
 * terminales: una tarea que llega a ellas ya no transiciona.
 *
 * <p>El orden importa: {@link #next()} y {@link #isBefore(TaskPhase)} se apoyan en él.
 */
public enum TaskPhase {
  INTAKE,
  REPO_SELECTION,
  IMPLEMENTATION,
  VERIFICATION,
  APPROVAL,
  DEPLOY_PRE,
  CLIENT_TEST,
  PROMOTION,
  DONE,
  ESCALATED,
  FAILED,
  CANCELLED;

  public boolean isTerminal() {
    return ordinal() >= DONE.ordinal();
  }

  /** Siguiente fase del recorrido normal ({@code PROMOTION} → {@code DONE}). */
  public TaskPhase next() {
    if (isTerminal()) {
      throw new IllegalStateException("Una fase terminal no tiene siguiente: " + this);
    }
    return values()[ordinal() + 1];
  }

  /**
   * Fases cuyo fallo no tiene salida: no admiten corrección ni reintento, así que la tarea termina
   * en {@link #FAILED}. Sin esto se quedarían vivas para siempre, como pasaba con un fallo del
   * responder.
   */
  public boolean failureIsTerminal() {
    return this == INTAKE || this == REPO_SELECTION;
  }

  public boolean isBefore(final TaskPhase other) {
    return ordinal() < other.ordinal();
  }
}

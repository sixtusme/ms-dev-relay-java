package es.colorbaby.microservices.dev.relay.config;

/**
 * Modo de detección de tareas de Jira asignadas.
 */
public enum SyncMode {

  /**
   * Solo vía webhook (POST /api/webhooks/jira).
   */
  WEBHOOK,

  /**
   * Solo vía búsqueda periódica por JQL.
   */
  POLLING,

  /**
   * Ambos simultáneamente.
   */
  BOTH;

  public boolean includesWebhook() {
    return this == WEBHOOK || this == BOTH;
  }

  public boolean includesPolling() {
    return this == POLLING || this == BOTH;
  }
}

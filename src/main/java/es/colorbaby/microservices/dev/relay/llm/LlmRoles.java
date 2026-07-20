package es.colorbaby.microservices.dev.relay.llm;

/**
 * Roles lógicos del LLM en sixai. Cada rol puede enrutarse a un modelo distinto vía
 * {@code maestro.llm.models.<rol>} cuando delante hay un gateway de modelos; si un rol no tiene
 * alias, se usa {@code maestro.llm.model}. Ver la nota de arquitectura /ai/gateway-de-modelos.
 */
public final class LlmRoles {

  /** Responder la tarea de Jira (comentario del responder). */
  public static final String RESPONDER = "responder";

  /** Elegir en qué repos abrir PR ({@code RepoSelector}). */
  public static final String SELECTOR = "selector";

  /** Planner del run de entrega (siguiente acción). Reservado para el bucle de auto-reparación. */
  public static final String PLANNER = "planner";

  /** Generar el fix/código de la PR. Reservado para el bucle de auto-reparación. */
  public static final String CODER = "coder";

  /** Diagnóstico de logs de build/deploy. Reservado para el bucle de auto-reparación. */
  public static final String DIAGNOSE = "diagnose";

  private LlmRoles() {
  }
}

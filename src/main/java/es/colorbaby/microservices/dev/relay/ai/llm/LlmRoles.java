package es.colorbaby.microservices.dev.relay.ai.llm;

/**
 * Roles lógicos del LLM en sixai. Cada rol puede enrutarse a un modelo distinto vía
 * {@code maestro.llm.models.<rol>} cuando delante hay un gateway de modelos; si un rol no tiene
 * alias, se usa {@code maestro.llm.model}. Ver la nota de arquitectura /ai/gateway-de-modelos.
 */
public final class LlmRoles {

  /** Responder la tarea de Jira (comentario del responder). */
  public static final String RESPONDER = "responder";

  /** Elegir en qué repos abrir PR ({@code SelectorAgent}). */
  public static final String SELECTOR = "selector";

  /** Interpretar la intención de un comando {@code /sixai} (salida acotada al catálogo). */
  public static final String ROUTER = "router";

  /** Planner del run de entrega (siguiente acción). Reservado para el bucle de auto-reparación. */
  public static final String PLANNER = "planner";

  /** Generar el fix/código de la PR. Reservado para el bucle de auto-reparación. */
  public static final String CODER = "coder";

  /** Diagnóstico de logs de build/deploy. Reservado para el bucle de auto-reparación. */
  public static final String DIAGNOSE = "diagnose";

  /** Embeddings para indexar y consultar el Knowledge (retrieval semántico). */
  public static final String EMBEDDING = "embedding";

  /** Revisar la calidad del ChangeSet que acaba de commitear el coder ({@code ReviewerAgent}). */
  public static final String REVIEWER = "reviewer";

  private LlmRoles() {
  }
}

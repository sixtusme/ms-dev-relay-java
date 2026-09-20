package es.colorbaby.microservices.dev.relay.llm;

import java.util.Map;

/**
 * Petición al LLM: los dos prompts, más el rol (que decide a qué modelo se enruta) y la metadata
 * (para traza y coste por tarea en un gateway de modelos). Rol y metadata son opcionales; contra un
 * Ollama directo se ignoran y todo usa el modelo por defecto.
 *
 * @param systemPrompt    instrucciones de comportamiento (rol del asistente)
 * @param userPrompt      contenido a responder
 * @param role            rol lógico (ver {@link LlmRoles}); elige el alias de modelo. Puede ser null
 * @param metadata        pares para el gateway (p. ej. {@code issue}). Nunca null tras el constructor
 * @param requireComplete si una respuesta cortada por el tope de tokens debe tratarse como fallo
 */
public record LlmRequest(String systemPrompt, String userPrompt, String role,
    Map<String, String> metadata, boolean requireComplete) {

  public LlmRequest {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  /** Petición simple, sin rol ni metadata: usa el modelo por defecto. */
  public static LlmRequest of(final String systemPrompt, final String userPrompt) {
    return new LlmRequest(systemPrompt, userPrompt, null, Map.of(), false);
  }

  /** Petición con rol y, si se aporta, el {@code issueKey} como metadata para traza/coste. */
  public static LlmRequest of(final String systemPrompt, final String userPrompt,
      final String role, final String issueKey) {
    return new LlmRequest(systemPrompt, userPrompt, role, metadata(issueKey), false);
  }

  /**
   * Igual, pero exigiendo respuesta completa: si el modelo la corta por llegar al tope de tokens,
   * la llamada falla en vez de devolver un trozo.
   *
   * <p>Es lo que necesita quien espera una respuesta <b>estructurada</b> (el coder devuelve JSON con
   * ficheros dentro): media respuesta no es una respuesta peor, es basura que no parsea. Prefiere un
   * fallo explícito, que se ve en el log y en {@code llm_call}, a un silencio que parece "el modelo
   * no propuso nada".
   */
  public static LlmRequest ofComplete(final String systemPrompt, final String userPrompt,
      final String role, final String issueKey) {
    return new LlmRequest(systemPrompt, userPrompt, role, metadata(issueKey), true);
  }

  private static Map<String, String> metadata(final String issueKey) {
    return issueKey == null || issueKey.isBlank() ? Map.of() : Map.of("issue", issueKey);
  }
}

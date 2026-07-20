package es.colorbaby.microservices.dev.relay.llm;

import java.util.Map;

/**
 * Petición al LLM: los dos prompts, más el rol (que decide a qué modelo se enruta) y la metadata
 * (para traza y coste por tarea en un gateway de modelos). Rol y metadata son opcionales; contra un
 * Ollama directo se ignoran y todo usa el modelo por defecto.
 *
 * @param systemPrompt instrucciones de comportamiento (rol del asistente)
 * @param userPrompt   contenido a responder
 * @param role         rol lógico (ver {@link LlmRoles}); elige el alias de modelo. Puede ser null
 * @param metadata     pares para el gateway (p. ej. {@code issue}). Nunca null tras el constructor
 */
public record LlmRequest(String systemPrompt, String userPrompt, String role,
    Map<String, String> metadata) {

  public LlmRequest {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  /** Petición simple, sin rol ni metadata: usa el modelo por defecto. */
  public static LlmRequest of(final String systemPrompt, final String userPrompt) {
    return new LlmRequest(systemPrompt, userPrompt, null, Map.of());
  }

  /** Petición con rol y, si se aporta, el {@code issueKey} como metadata para traza/coste. */
  public static LlmRequest of(final String systemPrompt, final String userPrompt,
      final String role, final String issueKey) {
    Map<String, String> metadata = issueKey == null || issueKey.isBlank()
        ? Map.of() : Map.of("issue", issueKey);
    return new LlmRequest(systemPrompt, userPrompt, role, metadata);
  }
}

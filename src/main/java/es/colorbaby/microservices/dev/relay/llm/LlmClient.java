package es.colorbaby.microservices.dev.relay.llm;

/**
 * Cliente de un modelo de lenguaje.
 *
 * <p>Abstrae el proveedor: hoy hay una implementación contra el formato compatible con OpenAI
 * (Ollama, OpenAI y cualquier gateway de modelos que hable ese formato, como LiteLLM). Para añadir
 * otro proveedor con otro formato basta con otra implementación de esta interfaz; el resto de la
 * app no cambia.
 */
public interface LlmClient {

  /**
   * Genera una respuesta para una petición con rol y metadata. El rol decide el modelo (vía
   * {@code maestro.llm.models.<rol>}); la metadata viaja al gateway para traza y coste por tarea.
   *
   * @param request prompts + rol + metadata
   * @return la respuesta del modelo, en texto plano
   * @throws LlmClientException si el proveedor falla o no devuelve contenido
   */
  String complete(LlmRequest request);

  /**
   * Atajo sin rol ni metadata (usa el modelo por defecto). Equivale a
   * {@code complete(LlmRequest.of(systemPrompt, userPrompt))}.
   *
   * @param systemPrompt instrucciones de comportamiento (rol del asistente)
   * @param userPrompt   contenido a responder
   * @return la respuesta del modelo, en texto plano
   */
  default String complete(final String systemPrompt, final String userPrompt) {
    return complete(LlmRequest.of(systemPrompt, userPrompt));
  }
}

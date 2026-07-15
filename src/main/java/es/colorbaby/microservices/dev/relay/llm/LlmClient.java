package es.colorbaby.microservices.dev.relay.llm;

/**
 * Cliente de un modelo de lenguaje.
 *
 * <p>Abstrae el proveedor: hoy hay una implementación contra el formato compatible con OpenAI
 * (Ollama, OpenAI…). Para añadir otro proveedor con otro formato (p. ej. Anthropic) basta con
 * otra implementación de esta interfaz; el resto de la app no cambia.
 */
public interface LlmClient {

  /**
   * Genera una respuesta a partir de un system prompt y un mensaje del usuario.
   *
   * @param systemPrompt instrucciones de comportamiento (rol del asistente)
   * @param userPrompt   contenido a responder
   * @return la respuesta del modelo, en texto plano
   * @throws LlmClientException si el proveedor falla o no devuelve contenido
   */
  String complete(String systemPrompt, String userPrompt);
}

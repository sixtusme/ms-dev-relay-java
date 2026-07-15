package es.colorbaby.microservices.dev.relay.llm;

/** Error al llamar al proveedor de LLM. */
public class LlmClientException extends RuntimeException {

  public LlmClientException(String message) {
    super(message);
  }

  public LlmClientException(String message, Throwable cause) {
    super(message, cause);
  }
}

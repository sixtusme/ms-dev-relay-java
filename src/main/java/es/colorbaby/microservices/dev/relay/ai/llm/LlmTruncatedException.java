package es.colorbaby.microservices.dev.relay.llm;

/**
 * El modelo llegó al tope de tokens y su respuesta salió cortada, en una llamada que exigía
 * respuesta completa ({@link LlmRequest#ofComplete}).
 *
 * <p>Tiene excepción propia porque es un fallo <b>de configuración, no del modelo</b>: el modelo hizo
 * su trabajo y fue el techo lo que le cortó la frase. Distinguirlo importa, porque el arreglo es
 * subir {@code maestro.llm.max-tokens-by-role.<rol>}, no cambiar de modelo ni tocar el prompt.
 */
public class LlmTruncatedException extends LlmClientException {

  public LlmTruncatedException(final String message) {
    super(message);
  }
}

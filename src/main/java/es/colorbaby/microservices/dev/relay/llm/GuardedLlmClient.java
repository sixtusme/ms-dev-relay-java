package es.colorbaby.microservices.dev.relay.llm;

import es.colorbaby.microservices.dev.relay.guardrail.SecretRedactor;
import lombok.RequiredArgsConstructor;

/**
 * Aplica la redacción de secretos a TODA llamada al modelo, en las dos direcciones.
 *
 * <p>Va como decorador y no repartido por los sitios que construyen prompts por la misma razón que
 * el registro de llamadas: así no hay forma de olvidarse. Cualquier prompt nuevo queda cubierto sin
 * que nadie tenga que acordarse de filtrarlo.
 *
 * <p>La salida también se redacta: el modelo puede repetir en su respuesta algo que venía en el
 * contexto, y esa respuesta acaba publicada en un comentario de Jira.
 */
@RequiredArgsConstructor
public class GuardedLlmClient implements LlmClient {

  private final LlmClient delegate;
  private final SecretRedactor redactor;

  @Override
  public String complete(final LlmRequest request) {
    final LlmRequest safe = new LlmRequest(
        redactor.redact(request.systemPrompt()),
        redactor.redact(request.userPrompt()),
        request.role(),
        request.metadata(),
        request.requireComplete());
    return redactor.redact(delegate.complete(safe));
  }
}

package es.colorbaby.microservices.dev.relay.llm;

import es.colorbaby.microservices.dev.relay.activity.LlmCall;
import es.colorbaby.microservices.dev.relay.activity.LlmCallRepository;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.activity.TaskRun;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Envuelve al cliente real para dejar constancia de CADA llamada al modelo: rol, modelo resuelto,
 * latencia, si fue bien y a qué tarea pertenece.
 *
 * <p>Va como decorador y no como código repetido en cada sitio porque así no hay forma de olvidarse
 * de registrar una llamada nueva: quien use {@link LlmClient} queda medido automáticamente.
 *
 * <p>Tokens y coste quedan a null: el cliente OpenAI-compatible todavía no expone el bloque
 * {@code usage} de la respuesta. Cuando lo haga, se rellenan aquí sin tocar a nadie más.
 */
@Slf4j
@RequiredArgsConstructor
public class RecordingLlmClient implements LlmClient {

  private final LlmClient delegate;
  private final LlmCallRepository llmCalls;
  private final TaskRecorder taskRecorder;
  private final LlmProperties properties;

  @Override
  public String complete(final LlmRequest request) {
    final long startedAt = System.currentTimeMillis();
    try {
      final String answer = delegate.complete(request);
      record(request, startedAt, true, null);
      return answer;
    } catch (RuntimeException e) {
      record(request, startedAt, false, e.getMessage());
      throw e;
    }
  }

  private void record(final LlmRequest request, final long startedAt, final boolean success,
      final String error) {
    try {
      final String issueKey = request.metadata().get("issue");
      final LlmCall call = new LlmCall();
      call.setIssueKey(issueKey);
      call.setRole(request.role() == null ? "unknown" : request.role());
      call.setModel(properties.modelFor(request.role()));
      call.setLatencyMs(System.currentTimeMillis() - startedAt);
      call.setSuccess(success);
      call.setError(truncate(error, LlmCall.ERROR_MAX));
      if (issueKey != null) {
        call.setTaskRunId(taskRecorder.current(issueKey).map(TaskRun::getId).orElse(null));
      }
      llmCalls.save(call);
    } catch (RuntimeException e) {
      // Medir nunca puede romper el trabajo real.
      log.warn("No se pudo registrar la llamada al LLM: {}", e.getMessage());
    }
  }

  private static String truncate(final String value, final int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}

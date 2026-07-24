package es.colorbaby.microservices.dev.relay.llm;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * Corta las llamadas al modelo cuando está caído o atascado, en vez de dejar que cada una se coma su
 * timeout completo.
 *
 * <p>El problema que resuelve es concreto: los timeouts del LLM son largos a propósito (los modelos
 * locales tardan), y las llamadas ocupan los hilos del pool de procesamiento. Si el modelo deja de
 * responder, cada tarea se queda esperando minutos y con varias tareas en cola sixai se atasca
 * entero. Con el cortocircuito abierto las llamadas fallan al instante y todo lo que depende del
 * LLM <b>degrada a su alternativa</b> (el coder al placeholder, el selector y el router a sus
 * filtros por palabras clave) en lugar de bloquearse.
 *
 * <p>Es el mismo patrón que ya usa el cliente de Jira; el del LLM no lo tenía.
 */
@Slf4j
public class ResilientLlmClient implements LlmClient {

  private final LlmClient delegate;
  private final CircuitBreaker circuitBreaker;

  public ResilientLlmClient(final LlmClient delegate, final int failureRateThreshold,
      final int minimumCalls, final int openSeconds) {
    this.delegate = delegate;
    this.circuitBreaker = CircuitBreaker.of("llm", CircuitBreakerConfig.custom()
        .failureRateThreshold(failureRateThreshold)
        .minimumNumberOfCalls(minimumCalls)
        .slidingWindowSize(minimumCalls * 2)
        .waitDurationInOpenState(Duration.ofSeconds(openSeconds))
        .permittedNumberOfCallsInHalfOpenState(1)
        // Una respuesta cortada NO es una caída del proveedor: el modelo respondió, y de sobra. Si
        // contara como fallo, un tope mal puesto en el coder abriría el circuito y dejaría sin IA
        // también al router, al selector y al responder, que no tienen nada que ver.
        .ignoreExceptions(LlmTruncatedException.class)
        .build());

    this.circuitBreaker.getEventPublisher().onStateTransition(event ->
        log.warn("Cortocircuito del LLM: {}", event.getStateTransition()));
  }

  @Override
  public String complete(final LlmRequest request) {
    try {
      return circuitBreaker.executeSupplier(() -> delegate.complete(request));
    } catch (CallNotPermittedException e) {
      // El cortocircuito está abierto: se falla YA para que el llamante use su alternativa.
      throw new LlmClientException(
          "El modelo no está respondiendo; llamadas cortadas temporalmente", e);
    }
  }
}

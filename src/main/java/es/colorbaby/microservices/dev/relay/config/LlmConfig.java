package es.colorbaby.microservices.dev.relay.config;

import es.colorbaby.microservices.dev.relay.activity.LlmCallRepository;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.guardrail.SecretRedactor;
import es.colorbaby.microservices.dev.relay.llm.GuardedLlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.OpenAiCompatibleLlmClient;
import es.colorbaby.microservices.dev.relay.llm.RecordingLlmClient;
import es.colorbaby.microservices.dev.relay.llm.ResilientLlmClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans del LLM: el cliente real envuelto en sus decoradores (resiliencia, medición y redacción).
 * Los RestTemplate los construye el propio cliente, uno por timeout, porque el timeout depende del
 * rol y no puede cambiarse por petición.
 */
@Configuration
@EnableConfigurationProperties({LlmProperties.class, ResponderProperties.class})
public class LlmConfig {

  /**
   * Cliente LLM. Hoy solo hay implementación compatible con OpenAI (Ollama, OpenAI…). Cuando se
   * añada otro proveedor con otro formato, se elige aquí según {@code properties.getProvider()}.
   *
   * <p>Los RestTemplate viven DENTRO del cliente y NO se exponen como bean: si lo fueran, el
   * {@code jiraRestTemplate} de la lib (que es {@code @ConditionalOnMissingBean}) no se crearía y
   * el cliente de Jira acabaría usando uno sin el interceptor de autenticación.
   */
  @Bean
  public LlmClient llmClient(final LlmProperties properties, final LlmCallRepository llmCalls,
      final TaskRecorder taskRecorder, final SecretRedactor redactor) {
    // Tres decoradores, cada uno con un trabajo, y en este orden a propósito (de dentro a fuera):
    //  1. OpenAiCompatible: la llamada real (con el timeout que corresponda al rol).
    //  2. Resilient: corta si el modelo no responde, para no comerse el timeout en cada llamada
    //     y bloquear los hilos de procesamiento.
    //  3. Recording: mide la llamada, incluidas las que corta el cortocircuito.
    //  4. Guarded (el más externo): redacta secretos antes de que salgan y en lo que vuelve.
    // Así ningún prompt nuevo puede olvidarse de ser medido, protegido ni filtrado.
    return new GuardedLlmClient(
        new RecordingLlmClient(
            new ResilientLlmClient(
                new OpenAiCompatibleLlmClient(properties),
                properties.getCircuitFailureRateThreshold(),
                properties.getCircuitMinimumCalls(),
                properties.getCircuitOpenSeconds()),
            llmCalls, taskRecorder, properties),
        redactor);
  }
}

package es.colorbaby.microservices.dev.relay.config;

import es.colorbaby.microservices.dev.relay.activity.LlmCallRepository;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.guardrail.SecretRedactor;
import es.colorbaby.microservices.dev.relay.ai.llm.AnthropicLlmClient;
import es.colorbaby.microservices.dev.relay.ai.llm.GuardedLlmClient;
import es.colorbaby.microservices.dev.relay.ai.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.ai.llm.OpenAiCompatibleLlmClient;
import es.colorbaby.microservices.dev.relay.ai.llm.RecordingLlmClient;
import es.colorbaby.microservices.dev.relay.ai.llm.ResilientLlmClient;
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
   * Cliente LLM, según {@code properties.getProvider()}: compatible con OpenAI (Ollama, OpenAI…) o
   * Anthropic (Claude).
   *
   * <p>Los RestTemplate viven DENTRO del cliente y NO se exponen como bean: si lo fueran, el
   * {@code jiraRestTemplate} de la lib (que es {@code @ConditionalOnMissingBean}) no se crearía y
   * el cliente de Jira acabaría usando uno sin el interceptor de autenticación.
   */
  @Bean
  public LlmClient llmClient(final LlmProperties properties, final LlmCallRepository llmCalls,
      final TaskRecorder taskRecorder, final SecretRedactor redactor) {
    // Tres decoradores, cada uno con un trabajo, y en este orden a propósito (de dentro a fuera):
    //  1. El proveedor (OpenAiCompatible o Anthropic): la llamada real, con el timeout del rol.
    //  2. Resilient: corta si el modelo no responde, para no comerse el timeout en cada llamada
    //     y bloquear los hilos de procesamiento.
    //  3. Recording: mide la llamada, incluidas las que corta el cortocircuito.
    //  4. Guarded (el más externo): redacta secretos antes de que salgan y en lo que vuelve.
    // Así ningún prompt nuevo puede olvidarse de ser medido, protegido ni filtrado.
    final LlmClient openAiCompatible = new OpenAiCompatibleLlmClient(properties);
    final LlmClient provider;
    if ("anthropic".equals(properties.getProvider())) {
      if (properties.isEnabled() && properties.getAnthropic().getApiKey().isBlank()) {
        throw new IllegalStateException(
            "maestro.llm.provider=anthropic sin maestro.llm.anthropic.api-key (ANTHROPIC_API_KEY)");
      }
      // Los embeddings siguen en el endpoint compatible con OpenAI: Anthropic no los ofrece.
      provider = new AnthropicLlmClient(properties, openAiCompatible);
    } else {
      provider = openAiCompatible;
    }
    return new GuardedLlmClient(
        new RecordingLlmClient(
            new ResilientLlmClient(
                provider,
                properties.getCircuitFailureRateThreshold(),
                properties.getCircuitMinimumCalls(),
                properties.getCircuitOpenSeconds()),
            llmCalls, taskRecorder, properties),
        redactor);
  }
}

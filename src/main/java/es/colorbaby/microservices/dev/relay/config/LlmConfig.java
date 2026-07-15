package es.colorbaby.microservices.dev.relay.config;

import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.OpenAiCompatibleLlmClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Beans del LLM. Mismo estilo que la autoconfiguración de Jira: un {@link RestTemplate} con sus
 * timeouts y el cliente por encima.
 */
@Configuration
@EnableConfigurationProperties({LlmProperties.class, ResponderProperties.class})
public class LlmConfig {

  /**
   * Cliente LLM. Hoy solo hay implementación compatible con OpenAI (Ollama, OpenAI…). Cuando se
   * añada otro proveedor con otro formato, se elige aquí según {@code properties.getProvider()}.
   *
   * <p>El {@link RestTemplate} se construye AQUÍ, privado, y NO se expone como bean: si lo fuera,
   * el {@code jiraRestTemplate} de la lib (que es {@code @ConditionalOnMissingBean}) no se crearía
   * y el cliente de Jira acabaría usando este RestTemplate sin el interceptor de autenticación.
   */
  @Bean
  public LlmClient llmClient(final LlmProperties properties) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(properties.getConnectTimeoutMs());
    factory.setReadTimeout(properties.getReadTimeoutMs());
    RestTemplate llmRestTemplate = new RestTemplate(factory);
    return new OpenAiCompatibleLlmClient(llmRestTemplate, properties);
  }
}

package es.colorbaby.microservices.dev.relay.config;

import es.colorbaby.microservices.dev.relay.jira.DryRunJiraClient;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClientImpl;
import es.colorbaby.microservices.dev.relay.jira.config.JiraProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Activa el modo simulación cuando {@code maestro.responder.dry-run=true}.
 *
 * <p>Declara un bean {@link JiraClient} que envuelve al real en {@link DryRunJiraClient}. Como el
 * bean {@code jiraClient} de la lib es {@code @ConditionalOnMissingBean}, al existir este se
 * inhibe el suyo y toda la app inyecta el decorador. Se reutiliza el {@code jiraRestTemplate} de la
 * lib (que ya lleva el interceptor de autenticación), así que las lecturas van a Jira de verdad.
 *
 * <p>Con dry-run apagado este bean no se crea ({@code @ConditionalOnProperty}) y la lib provee su
 * {@code jiraClient} normal.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "maestro.responder.dry-run", havingValue = "true")
public class DryRunConfig {

  @Bean
  public JiraClient jiraClient(RestTemplate jiraRestTemplate, JiraProperties jiraProperties) {
    log.warn("DRY-RUN activo: Sixai NO escribirá en Jira. Comentarios, transiciones y etiquetas se "
        + "omiten y se loguean con prefijo [DRY-RUN].");
    return new DryRunJiraClient(new JiraClientImpl(jiraRestTemplate, jiraProperties));
  }
}

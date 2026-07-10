package es.colorbaby.microservices.dev.relay.service;

import es.colorbaby.microservices.dev.relay.event.TriggerSource;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;

/**
 * Punto de entrada compartido por el webhook y el polling: aplica el filtro
 * de elegibilidad y, si la issue es elegible, comenta la confirmación en
 * Jira y publica {@link es.colorbaby.microservices.dev.relay.event.IssueEligibleEvent}.
 */
public interface IssueTriggerService {

  void process(JiraIssueDto issue, TriggerSource source);
}

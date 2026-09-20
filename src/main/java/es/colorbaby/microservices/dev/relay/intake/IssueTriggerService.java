package es.colorbaby.microservices.dev.relay.intake;

import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;

/**
 * Punto de entrada compartido por el webhook y el polling: aplica el filtro
 * de elegibilidad y, si la issue es elegible, comenta la confirmación en
 * Jira y publica {@link IssueEligibleEvent}.
 */
public interface IssueTriggerService {

  void process(JiraIssueDto issue, TriggerSource source);
}

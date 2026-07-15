package es.colorbaby.microservices.dev.relay.service.impl;

import es.colorbaby.microservices.dev.relay.event.IssueEligibleEvent;
import es.colorbaby.microservices.dev.relay.event.TriggerSource;
import es.colorbaby.microservices.dev.relay.filter.EligibilityResult;
import es.colorbaby.microservices.dev.relay.filter.JiraIssueEligibilityFilter;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClientException;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraCommentDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraUserDto;
import es.colorbaby.microservices.dev.relay.config.AsyncConfig;
import es.colorbaby.microservices.dev.relay.service.IssueTriggerService;
import es.colorbaby.microservices.dev.relay.tracker.ProcessedIssuesTracker;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class IssueTriggerServiceImpl implements IssueTriggerService {

  private final JiraClient jiraClient;
  private final JiraIssueEligibilityFilter eligibilityFilter;
  private final ProcessedIssuesTracker processedIssuesTracker;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Procesamiento en background: el webhook ya ha respondido 200 y el polling
   * no se bloquea. Cualquier error queda contenido aquí (se loguea, no se
   * propaga) para no provocar reintentos externos ni tumbar el ciclo.
   */
  @Async(AsyncConfig.JIRA_TASK_EXECUTOR)
  @Override
  public void process(JiraIssueDto issue, TriggerSource source) {
    if (issue == null || issue.getKey() == null) {
      return;
    }
    String issueKey = issue.getKey();
    try {
      if (!eligibilityFilter.isAssigneeAllowed(issue)) {
        log.debug("Issue {} descartada: el asignado no está en allowed-assignees", issueKey);
        return;
      }

      List<JiraCommentDto> comments = jiraClient.getComments(issueKey);
      Optional<EligibilityResult> eligibility = eligibilityFilter.evaluate(issue, comments);
      if (eligibility.isEmpty()) {
        log.debug("Issue {} descartada: ninguno de sus {} comentarios cumple (palabra clave "
            + "del asignado)", issueKey, comments.size());
        return;
      }
      if (!processedIssuesTracker.markIfNew(issueKey)) {
        log.debug("Issue {} ya procesada, se ignora ({})", issueKey, source);
        return;
      }

      log.info("Issue {} elegible ({}), confirmando y publicando evento", issueKey, source);
      confirmDetection(issueKey);

      JiraUserDto assignee = issue.getFields() == null ? null : issue.getFields().getAssignee();
      eventPublisher.publishEvent(new IssueEligibleEvent(
          issueKey,
          assignee == null ? null : assignee.getAccountId(),
          eligibility.get().triggeringComment().getId(),
          source,
          Instant.now()));
    } catch (RuntimeException e) {
      log.error("Error procesando la issue {} ({})", issueKey, source, e);
    }
  }

  private void confirmDetection(String issueKey) {
    try {
      jiraClient.addComment(issueKey,
          "Detectada por sixai: esta tarea ha sido identificada como elegible "
              + "y será procesada automáticamente.");
    } catch (JiraClientException e) {
      log.warn("No se pudo comentar la confirmación de detección en {}", issueKey, e);
    }
  }
}

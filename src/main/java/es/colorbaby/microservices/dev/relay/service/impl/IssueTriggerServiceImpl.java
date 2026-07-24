package es.colorbaby.microservices.dev.relay.service.impl;

import es.colorbaby.microservices.dev.relay.command.CommandService;
import es.colorbaby.microservices.dev.relay.event.IssueEligibleEvent;
import es.colorbaby.microservices.dev.relay.event.TriggerSource;
import es.colorbaby.microservices.dev.relay.filter.EligibilityResult;
import es.colorbaby.microservices.dev.relay.filter.JiraIssueEligibilityFilter;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.jira.util.JiraTextExtractor;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraCommentDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFields;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraTransitionDtoTo;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraUserDto;
import es.colorbaby.microservices.dev.relay.config.AsyncConfig;
import es.colorbaby.microservices.dev.relay.config.CommandProperties;
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
  private final CommandService commandService;
  private final CommandProperties commandProperties;

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
      // Tarea ya arrancada (En curso / TEST): no se re-arranca, se atienden sus comandos.
      if (commandProperties.isEnabled() && isCommandStatus(issue)) {
        commandService.handle(issue);
        return;
      }
      if (!eligibilityFilter.isStatusAllowed(issue)) {
        log.debug("Issue {} descartada: su estado no está en filter.statuses", issueKey);
        return;
      }
      if (eligibilityFilter.isAlreadyProcessed(issue)) {
        log.debug("Issue {} descartada: ya tiene la etiqueta de procesada", issueKey);
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

      log.info("Issue {} elegible ({}), publicando evento", issueKey, source);

      JiraUserDto assignee = issue.getFields() == null ? null : issue.getFields().getAssignee();
      JiraCommentDto trigger = eligibility.get().triggeringComment();
      JiraUserDto triggerAuthor = trigger.getAuthor();
      eventPublisher.publishEvent(new IssueEligibleEvent(
          issueKey,
          assignee == null ? null : assignee.getAccountId(),
          trigger.getId(),
          triggerAuthor == null ? null : triggerAuthor.getAccountId(),
          triggerAuthor == null ? null : triggerAuthor.getDisplayName(),
          JiraTextExtractor.extractPlainText(trigger.getBody()),
          source,
          Instant.now()));
    } catch (RuntimeException e) {
      log.error("Error procesando la issue {} ({})", issueKey, source, e);
    }
  }

  /** True si la tarea está en un estado donde se aceptan comandos (En curso, TEST…). */
  private boolean isCommandStatus(final JiraIssueDto issue) {
    final List<String> statuses = commandProperties.getStatuses();
    if (statuses == null || statuses.isEmpty()) {
      return false;
    }
    final JiraIssueDtoFields fields = issue.getFields();
    final JiraTransitionDtoTo status = fields == null ? null : fields.getStatus();
    final String name = status == null ? null : status.getName();
    if (name == null) {
      return false;
    }
    return statuses.stream().anyMatch(s -> s != null && s.strip().equalsIgnoreCase(name.strip()));
  }
}

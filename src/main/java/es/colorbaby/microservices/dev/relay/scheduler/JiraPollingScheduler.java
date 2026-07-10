package es.colorbaby.microservices.dev.relay.scheduler;

import es.colorbaby.microservices.dev.relay.config.JiraFilterProperties;
import es.colorbaby.microservices.dev.relay.event.TriggerSource;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.service.IssueTriggerService;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Busca periódicamente por JQL las issues asignadas a la lista de usuarios
 * permitidos y las pasa por el mismo filtro de elegibilidad que el webhook,
 * vía IssueTriggerService. Solo activo si maestro.jira.sync.mode es POLLING
 * o BOTH.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression(
    "'${maestro.jira.sync.mode:POLLING}' == 'POLLING' or '${maestro.jira.sync.mode:POLLING}' == 'BOTH'")
public class JiraPollingScheduler {

  private final JiraClient jiraClient;
  private final JiraFilterProperties filterProperties;
  private final IssueTriggerService issueTriggerService;

  @Scheduled(fixedDelayString = "${maestro.jira.sync.polling-interval-ms:60000}")
  public void pollAssignedIssues() {
    List<String> allowedAssignees = filterProperties.getAllowedAssignees();
    if (allowedAssignees == null || allowedAssignees.isEmpty()) {
      log.debug("maestro.jira.filter.allowed-assignees está vacío, no hay nada que sondear");
      return;
    }

    List<JiraIssueDto> issues;
    try {
      issues = jiraClient.searchIssuesByJql(buildJql(allowedAssignees));
    } catch (RuntimeException e) {
      // Un fallo de la búsqueda no debe tumbar el scheduler: el próximo ciclo
      // reintentará.
      log.error("Fallo sondeando issues por JQL; se reintentará en el próximo ciclo", e);
      return;
    }

    for (JiraIssueDto issue : issues) {
      try {
        issueTriggerService.process(issue, TriggerSource.POLLING);
      } catch (RuntimeException e) {
        // Aislamos por issue para que una problemática no impida procesar el resto.
        log.error("Fallo procesando la issue {} en el polling",
            issue == null ? "?" : issue.getKey(), e);
      }
    }
  }

  private String buildJql(List<String> allowedAssignees) {
    String assignees = allowedAssignees.stream()
        .map(a -> "\"" + a.replace("\"", "") + "\"")
        .collect(Collectors.joining(","));
    return "assignee in (" + assignees + ") ORDER BY updated DESC";
  }
}

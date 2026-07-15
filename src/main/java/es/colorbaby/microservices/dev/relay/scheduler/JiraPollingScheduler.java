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

    String jql = buildJql(allowedAssignees);
    log.debug("Polling: buscando issues con JQL [{}]", jql);

    List<JiraIssueDto> issues;
    try {
      issues = jiraClient.searchIssuesByJql(jql);
    } catch (RuntimeException e) {
      // Un fallo de la búsqueda no debe tumbar el scheduler: el próximo ciclo
      // reintentará.
      log.error("Fallo sondeando issues por JQL; se reintentará en el próximo ciclo", e);
      return;
    }

    log.debug("Polling: {} issues asignadas encontradas", issues.size());

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

    StringBuilder jql = new StringBuilder("assignee in (").append(assignees).append(")");

    // Solo las tareas en los estados configurados (p. ej. "TAREAS EN COLA"): así Jira devuelve
    // pocas en vez de todas las asignadas, y no se tocan las finalizadas ni las ya en curso.
    List<String> statuses = filterProperties.getStatuses();
    if (statuses != null && !statuses.isEmpty()) {
      String values = statuses.stream()
          .map(s -> "\"" + s.replace("\"", "") + "\"")
          .collect(Collectors.joining(","));
      jql.append(" AND status in (").append(values).append(")");
    }

    // Excluye las ya procesadas por su etiqueta de idempotencia. "labels IS EMPTY OR ..." porque en
    // JQL una tarea sin etiquetas no la devuelve un simple "labels NOT IN (...)".
    String processedLabel = filterProperties.getProcessedLabel();
    if (processedLabel != null && !processedLabel.isBlank()) {
      String label = processedLabel.replace("\"", "");
      jql.append(" AND (labels IS EMPTY OR labels NOT IN (\"").append(label).append("\"))");
    }

    return jql.append(" ORDER BY updated DESC").toString();
  }
}

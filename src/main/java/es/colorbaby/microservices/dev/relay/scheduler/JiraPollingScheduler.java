package es.colorbaby.microservices.dev.relay.scheduler;

import es.colorbaby.microservices.dev.relay.config.CommandProperties;
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
  private final CommandProperties commandProperties;
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

  /**
   * El JQL cubre DOS poblaciones distintas, en OR: las tareas candidatas a arrancar (en cola y sin
   * la etiqueta de procesada) y las ya arrancadas donde pueden llegar comandos (En curso, TEST).
   * Sin ese OR, las de comandos nunca se traerían: tienen otro estado y además ya llevan etiqueta.
   */
  private String buildJql(List<String> allowedAssignees) {
    StringBuilder jql = new StringBuilder("assignee in (")
        .append(quoted(allowedAssignees)).append(")");

    String startClause = startClause();
    String commandClause = commandClause();

    if (startClause != null && commandClause != null) {
      jql.append(" AND ((").append(startClause).append(") OR (").append(commandClause).append("))");
    } else if (startClause != null) {
      jql.append(" AND ").append(startClause);
    } else if (commandClause != null) {
      jql.append(" AND ").append(commandClause);
    }

    return jql.append(" ORDER BY updated DESC").toString();
  }

  /** Candidatas a ARRANQUE: en los estados de cola y sin la etiqueta de idempotencia. */
  private String startClause() {
    StringBuilder clause = new StringBuilder();
    List<String> statuses = filterProperties.getStatuses();
    if (statuses != null && !statuses.isEmpty()) {
      clause.append("status in (").append(quoted(statuses)).append(")");
    }
    // "labels IS EMPTY OR ..." porque en JQL una tarea sin etiquetas no la devuelve un simple
    // "labels NOT IN (...)".
    String processedLabel = filterProperties.getProcessedLabel();
    if (processedLabel != null && !processedLabel.isBlank()) {
      if (!clause.isEmpty()) {
        clause.append(" AND ");
      }
      clause.append("(labels IS EMPTY OR labels NOT IN (\"")
          .append(processedLabel.replace("\"", "")).append("\"))");
    }
    return clause.isEmpty() ? null : clause.toString();
  }

  /**
   * Candidatas a COMANDO: ya en curso o en TEST. Aquí NO se excluye la etiqueta de procesada: son
   * justo tareas ya arrancadas, y lo que se busca en ellas es la conversación.
   */
  private String commandClause() {
    if (!commandProperties.isEnabled()) {
      return null;
    }
    List<String> statuses = commandProperties.getStatuses();
    if (statuses == null || statuses.isEmpty()) {
      return null;
    }
    return "status in (" + quoted(statuses) + ")";
  }

  private static String quoted(List<String> values) {
    return values.stream()
        .map(v -> "\"" + v.replace("\"", "") + "\"")
        .collect(Collectors.joining(","));
  }
}

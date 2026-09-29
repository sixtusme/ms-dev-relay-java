package es.colorbaby.microservices.dev.relay.ai.tool.impl.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import es.colorbaby.microservices.dev.relay.ai.tool.Tool;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolSchema;
import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolRisk;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Lee una issue de Jira por su key. Envuelve {@link JiraClient#getIssue}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JiraGetIssueTool implements Tool {

  private final JiraClient jiraClient;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "jira.get_issue";
  }

  @Override
  public String description() {
    return "Obtiene una issue de Jira por su key (ej. COLORBABY-123).";
  }

  @Override
  public ToolSchema inputSchema() {
    return new ToolSchema("""
        {"type":"object","required":["issueKey"],"properties":{
          "issueKey":{"type":"string","description":"Key de la issue, ej. COLORBABY-123"}
        }}""");
  }

  @Override
  public ToolRisk risk() {
    return ToolRisk.READ;
  }

  @Override
  public ToolResult execute(final ToolContext context, final ToolArguments arguments) {
    final String issueKey = arguments.requireString("issueKey");
    final JiraIssueDto issue = jiraClient.getIssue(issueKey);
    return ToolResult.success(toJson(issue), issue);
  }

  private String toJson(final JiraIssueDto issue) {
    try {
      return objectMapper.writeValueAsString(issue);
    } catch (JsonProcessingException e) {
      log.warn("No se pudo serializar la issue de Jira a JSON", e);
      return String.valueOf(issue);
    }
  }
}

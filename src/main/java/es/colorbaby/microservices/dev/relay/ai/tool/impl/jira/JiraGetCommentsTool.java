package es.colorbaby.microservices.dev.relay.ai.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.colorbaby.microservices.dev.relay.ai.tool.Tool;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolSchema;
import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolRisk;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraCommentDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Lee los comentarios de una issue de Jira. Envuelve {@link JiraClient#getComments}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JiraGetCommentsTool implements Tool {

  private final JiraClient jiraClient;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "jira.get_comments";
  }

  @Override
  public String description() {
    return "Lee todos los comentarios de una issue de Jira.";
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
    final List<JiraCommentDto> comments = jiraClient.getComments(issueKey);
    return ToolResult.success(toJson(comments), comments);
  }

  private String toJson(final List<JiraCommentDto> comments) {
    try {
      return objectMapper.writeValueAsString(comments);
    } catch (JsonProcessingException e) {
      log.warn("No se pudo serializar los comentarios de Jira a JSON", e);
      return String.valueOf(comments);
    }
  }
}

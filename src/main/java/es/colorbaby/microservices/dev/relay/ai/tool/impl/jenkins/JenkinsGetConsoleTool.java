package es.colorbaby.microservices.dev.relay.ai.tool.impl;

import es.colorbaby.microservices.dev.relay.ai.tool.Tool;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolSchema;
import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolRisk;
import es.colorbaby.microservices.dev.relay.jenkins.client.JenkinsClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Consola de un build de Jenkins. Envuelve {@link JenkinsClient#getConsoleLog}.
 */
@Component
@RequiredArgsConstructor
public class JenkinsGetConsoleTool implements Tool {

  private final JenkinsClient jenkinsClient;

  @Override
  public String name() {
    return "jenkins.get_console";
  }

  @Override
  public String description() {
    return "Log de consola (texto plano) de un build concreto de Jenkins.";
  }

  @Override
  public ToolSchema inputSchema() {
    return new ToolSchema("""
        {"type":"object","required":["jobPath","buildNumber"],"properties":{
          "jobPath":{"type":"string","description":"Ruta del job tras la URL base"},
          "buildNumber":{"type":"integer","description":"Número de build"}
        }}""");
  }

  @Override
  public ToolRisk risk() {
    return ToolRisk.READ;
  }

  @Override
  public ToolResult execute(final ToolContext context, final ToolArguments arguments) {
    final String jobPath = arguments.requireString("jobPath");
    final int buildNumber = arguments.getInt("buildNumber");
    final String console = jenkinsClient.getConsoleLog(jobPath, buildNumber);
    return ToolResult.success(console, console);
  }
}

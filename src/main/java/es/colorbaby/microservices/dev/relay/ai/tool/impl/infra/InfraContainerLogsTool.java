package es.colorbaby.microservices.dev.relay.ai.tool.impl;

import es.colorbaby.microservices.dev.relay.ai.tool.Tool;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolSchema;
import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolRisk;
import es.colorbaby.microservices.dev.relay.infra.client.InfraClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Últimas líneas del log de un contenedor. Envuelve {@link InfraClient#containerLogs}. No existe
 * (a propósito) una tool de "ejecuta este comando": el catálogo de infra es cerrado.
 */
@Component
@RequiredArgsConstructor
public class InfraContainerLogsTool implements Tool {

  private static final int DEFAULT_LINES = 200;

  private final InfraClient infraClient;

  @Override
  public String name() {
    return "infra.container_logs";
  }

  @Override
  public String description() {
    return "Últimas líneas del log de un contenedor en una máquina.";
  }

  @Override
  public ToolSchema inputSchema() {
    return new ToolSchema("""
        {"type":"object","required":["host","container"],"properties":{
          "host":{"type":"string","description":"Máquina destino, ej. devops03"},
          "container":{"type":"string","description":"Nombre del contenedor"},
          "lines":{"type":"integer","description":"Cuántas líneas del final (por defecto 200)"}
        }}""");
  }

  @Override
  public ToolRisk risk() {
    return ToolRisk.READ;
  }

  @Override
  public ToolResult execute(final ToolContext context, final ToolArguments arguments) {
    final String host = arguments.requireString("host");
    final String container = arguments.requireString("container");
    final int lines = arguments.values().containsKey("lines")
        ? arguments.getInt("lines") : DEFAULT_LINES;
    final String logs = infraClient.containerLogs(host, container, lines);
    return ToolResult.success(logs, logs);
  }
}

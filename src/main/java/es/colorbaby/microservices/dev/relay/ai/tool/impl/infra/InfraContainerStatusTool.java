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
 * Estado de un contenedor en una máquina. Envuelve {@link InfraClient#containerStatus}.
 */
@Component
@RequiredArgsConstructor
public class InfraContainerStatusTool implements Tool {

  private final InfraClient infraClient;

  @Override
  public String name() {
    return "infra.container_status";
  }

  @Override
  public String description() {
    return "Estado de un contenedor (arriba/abajo, reinicios, código de salida) en una máquina.";
  }

  @Override
  public ToolSchema inputSchema() {
    return new ToolSchema("""
        {"type":"object","required":["host","container"],"properties":{
          "host":{"type":"string","description":"Máquina destino, ej. devops03"},
          "container":{"type":"string","description":"Nombre del contenedor"}
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
    final String status = infraClient.containerStatus(host, container);
    return ToolResult.success(status, status);
  }
}

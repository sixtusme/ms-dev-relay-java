package es.colorbaby.microservices.dev.relay.ai.tool.impl;

import es.colorbaby.microservices.dev.relay.ai.tool.Tool;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolSchema;
import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolRisk;
import es.colorbaby.microservices.dev.relay.harbor.client.HarborClient;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resumen del escaneo de vulnerabilidades de una imagen. Envuelve {@link HarborClient#scan}.
 */
@Component
@RequiredArgsConstructor
public class HarborScanTool implements Tool {

  private final HarborClient harborClient;

  @Override
  public String name() {
    return "harbor.scan";
  }

  @Override
  public String description() {
    return "Resumen del escaneo de vulnerabilidades (Trivy) de una imagen y etiqueta concretas.";
  }

  @Override
  public ToolSchema inputSchema() {
    return new ToolSchema("""
        {"type":"object","required":["repository","tag"],"properties":{
          "repository":{"type":"string","description":"Nombre de la imagen dentro del proyecto"},
          "tag":{"type":"string","description":"Etiqueta (versión) concreta"}
        }}""");
  }

  @Override
  public ToolRisk risk() {
    return ToolRisk.READ;
  }

  @Override
  public ToolResult execute(final ToolContext context, final ToolArguments arguments) {
    final String repository = arguments.requireString("repository");
    final String tag = arguments.requireString("tag");
    final Optional<HarborClient.ScanSummary> scan = harborClient.scan(repository, tag);
    if (scan.isEmpty()) {
      return ToolResult.failure("No hay escaneo para " + repository + ":" + tag);
    }
    return ToolResult.success(scan.get().toString(), scan.get());
  }
}

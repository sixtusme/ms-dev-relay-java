package es.colorbaby.microservices.dev.relay.ai.tool.impl.github;

import es.colorbaby.microservices.dev.relay.ai.tool.Tool;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolSchema;
import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolRisk;
import es.colorbaby.microservices.dev.relay.github.client.GithubClient;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Escribe varios ficheros en una rama con un único commit. Envuelve {@link
 * GithubClient#commitFiles}. Es la ÚNICA tool que escribe código en un repositorio; por eso lleva
 * {@link ToolRisk#WRITE} y por eso {@link ChangeSetToolGuardrail} se aplica exactamente a ella.
 */
@Component
@RequiredArgsConstructor
public class GithubCommitTool implements Tool {

  private final GithubClient githubClient;

  @Override
  public String name() {
    return "github.commit";
  }

  @Override
  public String description() {
    return "Escribe varios ficheros en una rama de un repositorio de GitHub, en un único commit.";
  }

  @Override
  public ToolSchema inputSchema() {
    return new ToolSchema("""
        {"type":"object","required":["repo","branch","files","message"],"properties":{
          "repo":{"type":"string","description":"Nombre del repo, sin la org"},
          "branch":{"type":"string","description":"Rama sobre la que se commitea"},
          "files":{"type":"object","description":"Ruta -> contenido completo del fichero"},
          "message":{"type":"string","description":"Mensaje del commit"}
        }}""");
  }

  @Override
  public ToolRisk risk() {
    return ToolRisk.WRITE;
  }

  @Override
  public ToolResult execute(final ToolContext context, final ToolArguments arguments) {
    final String repo = arguments.requireString("repo");
    final String branch = arguments.requireString("branch");
    final String message = arguments.requireString("message");
    final Map<String, String> files = asStringMap(arguments.values().get("files"));
    if (files.isEmpty()) {
      return ToolResult.failure("No hay ficheros que commitear");
    }
    final String sha = githubClient.commitFiles(repo, branch, files, message);
    return ToolResult.success("Commit " + sha + " (" + files.size() + " fichero(s))", sha);
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> asStringMap(final Object value) {
    if (!(value instanceof Map<?, ?> raw)) {
      return Map.of();
    }
    final Map<String, String> result = new LinkedHashMap<>();
    for (final Map.Entry<?, ?> entry : raw.entrySet()) {
      result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
    }
    return result;
  }
}

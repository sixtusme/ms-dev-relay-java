package es.colorbaby.microservices.dev.relay.ai.tool.impl.github;

import es.colorbaby.microservices.dev.relay.ai.tool.Tool;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolSchema;
import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolRisk;
import es.colorbaby.microservices.dev.relay.github.client.GithubClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Escribe y/o borra varios ficheros en una rama con un único commit. Envuelve {@link
 * GithubClient#commitFiles(String, String, Map, List, String)}. Es la ÚNICA tool que escribe (o
 * borra) código en un repositorio; por eso lleva {@link ToolRisk#WRITE} y por eso
 * {@code ChangeSetToolGuardrail} se aplica exactamente a ella.
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
    return "Escribe y/o borra varios ficheros en una rama de un repositorio de GitHub, en un "
        + "único commit.";
  }

  @Override
  public ToolSchema inputSchema() {
    return new ToolSchema("""
        {"type":"object","required":["repo","branch","message"],"properties":{
          "repo":{"type":"string","description":"Nombre del repo, sin la org"},
          "branch":{"type":"string","description":"Rama sobre la que se commitea"},
          "files":{"type":"object","description":"Ruta -> contenido completo del fichero a crear o actualizar"},
          "deletes":{"type":"array","items":{"type":"string"},"description":"Rutas a eliminar"},
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
    final List<String> deletes = asStringList(arguments.values().get("deletes"));
    if (files.isEmpty() && deletes.isEmpty()) {
      return ToolResult.failure("No hay cambios que commitear");
    }
    final String sha = githubClient.commitFiles(repo, branch, files, deletes, message);
    return ToolResult.success(
        "Commit " + sha + " (" + files.size() + " fichero(s), " + deletes.size() + " borrado(s))",
        sha);
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

  private List<String> asStringList(final Object value) {
    if (!(value instanceof List<?> raw)) {
      return List.of();
    }
    final List<String> result = new ArrayList<>(raw.size());
    for (final Object item : raw) {
      result.add(String.valueOf(item));
    }
    return result;
  }
}

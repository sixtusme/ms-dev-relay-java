package es.colorbaby.microservices.dev.relay.ai.tool.impl.github;

import es.colorbaby.microservices.dev.relay.ai.tool.Tool;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolSchema;
import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolRisk;
import es.colorbaby.microservices.dev.relay.github.client.GithubClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Rutas de todos los ficheros de una rama de un repo. Envuelve {@link GithubClient#listPaths}.
 */
@Component
@RequiredArgsConstructor
public class GithubListPathsTool implements Tool {

  private final GithubClient githubClient;

  @Override
  public String name() {
    return "github.list_paths";
  }

  @Override
  public String description() {
    return "Lista las rutas de todos los ficheros de una rama de un repositorio de GitHub.";
  }

  @Override
  public ToolSchema inputSchema() {
    return new ToolSchema("""
        {"type":"object","required":["repo","ref"],"properties":{
          "repo":{"type":"string","description":"Nombre del repo, sin la org"},
          "ref":{"type":"string","description":"Rama o SHA"}
        }}""");
  }

  @Override
  public ToolRisk risk() {
    return ToolRisk.READ;
  }

  @Override
  public ToolResult execute(final ToolContext context, final ToolArguments arguments) {
    final String repo = arguments.requireString("repo");
    final String ref = arguments.requireString("ref");
    final List<String> paths = githubClient.listPaths(repo, ref);
    return ToolResult.success(String.join("\n", paths), paths);
  }
}

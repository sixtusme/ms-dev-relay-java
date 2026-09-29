package es.colorbaby.microservices.dev.relay.ai.tool.impl.github;

import es.colorbaby.microservices.dev.relay.ai.tool.Tool;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolSchema;
import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolRisk;
import es.colorbaby.microservices.dev.relay.github.client.GithubClient;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Contenido de un fichero de un repo de GitHub. Envuelve {@link GithubClient#getFileContent}.
 */
@Component
@RequiredArgsConstructor
public class GithubReadFileTool implements Tool {

  private final GithubClient githubClient;

  @Override
  public String name() {
    return "github.read_file";
  }

  @Override
  public String description() {
    return "Lee el contenido de un fichero de una rama de un repositorio de GitHub.";
  }

  @Override
  public ToolSchema inputSchema() {
    return new ToolSchema("""
        {"type":"object","required":["repo","ref","path"],"properties":{
          "repo":{"type":"string","description":"Nombre del repo, sin la org"},
          "ref":{"type":"string","description":"Rama o SHA"},
          "path":{"type":"string","description":"Ruta del fichero en el repo"}
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
    final String path = arguments.requireString("path");
    final Optional<GithubClient.FileContent> file = githubClient.getFileContent(repo, ref, path);
    if (file.isEmpty()) {
      return ToolResult.failure("No existe el fichero: " + path);
    }
    return ToolResult.success(file.get().content(), file.get());
  }
}

package es.colorbaby.microservices.dev.relay.coder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.colorbaby.microservices.dev.relay.config.CoderProperties;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.github.client.GithubClient;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * El coder: genera el código real de la tarea dentro de la rama de la PR, en vez de un placeholder.
 * Trabaja en dos pasadas con el LLM (rol {@code coder}): primero decide qué ficheros existentes leer
 * a partir del árbol del repo, y luego, con esos ficheros como contexto, devuelve los cambios en
 * JSON. Los cambios se aplican por upsert (crear o actualizar con SHA) sobre la rama.
 *
 * <p>Es best-effort y de <b>primer borrador</b>: su salida va a una draft-PR que un humano aprueba
 * antes del merge (Fase 3), así que no necesita ser perfecta. Todo depende de
 * {@code maestro.coder.enabled} y de {@code maestro.llm.enabled}; con {@code dry-run} propone y
 * loguea sin commitear. Si está apagado, falla o no propone nada, el llamante cae al placeholder.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoderService {

  private static final String PLAN_PROMPT =
      "Eres el coder de Sixai. Te doy una tarea y el árbol de ficheros de un repositorio. Dime qué "
      + "ficheros EXISTENTES del árbol necesitas leer para implementarla. Responde ÚNICAMENTE con "
      + "JSON válido, sin texto ni comillas triples alrededor, con esta forma: "
      + "{\"read\": [\"ruta1\", \"ruta2\"]}. Usa solo rutas que aparezcan literalmente en el árbol. "
      + "Si no necesitas leer ninguno, responde {\"read\": []}.";

  private static final String GENERATE_PROMPT =
      "Eres el coder de Sixai. Implementa la tarea sobre el repositorio. Responde ÚNICAMENTE con "
      + "JSON válido, sin texto ni comillas triples alrededor, con esta forma: {\"summary\": "
      + "\"qué has hecho\", \"changes\": [{\"path\": \"ruta\", \"action\": \"CREATE|UPDATE\", "
      + "\"content\": \"contenido COMPLETO del fichero\"}]}. Incluye SIEMPRE el contenido completo "
      + "de cada fichero que cambies (nunca diffs ni fragmentos). Toca solo los ficheros "
      + "imprescindibles. Respeta el estilo y las convenciones del contexto. Si no puedes hacerla "
      + "con la información disponible, responde {\"summary\": \"\", \"changes\": []}.";

  private final GithubClient githubClient;
  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final CoderProperties properties;
  private final ObjectMapper objectMapper;

  /**
   * Intenta implementar la tarea en la rama. Devuelve true si commiteó al menos un fichero; false si
   * está apagado, en dry-run, no propuso nada o falló (el llamante cae entonces al placeholder).
   */
  public boolean tryImplement(final String issueKey, final String repo, final String branch,
      final String summary, final String description) {
    if (!properties.isEnabled() || !llmProperties.isEnabled()) {
      return false;
    }
    try {
      final ChangeSet changeSet = generate(issueKey, repo, branch, summary, description);
      if (changeSet.isEmpty()) {
        log.info("El coder no propuso cambios para {} en {}", issueKey, repo);
        return false;
      }
      if (properties.isDryRun()) {
        log.info("[DRY-RUN] El coder cambiaría en {}: {}", repo, paths(changeSet));
        return false;
      }
      apply(repo, branch, changeSet, issueKey);
      final int n = changeSet.changes().size();
      log.info("Coder commiteó {} fichero(s) en {} para {}", n, repo, issueKey);
      return true;
    } catch (RuntimeException e) {
      log.warn("El coder falló en {} para {}: {}", repo, issueKey, e.getMessage());
      return false;
    }
  }

  private ChangeSet generate(final String issueKey, final String repo, final String ref,
      final String summary, final String description) {
    final List<String> tree = cappedTree(repo, ref);
    final String task = task(summary, description);

    final List<String> toRead = planFilesToRead(issueKey, task, tree);
    final Map<String, String> context = readContext(repo, ref, toRead);

    return generateChanges(issueKey, task, tree, context);
  }

  private List<String> cappedTree(final String repo, final String ref) {
    final List<String> all = githubClient.listPaths(repo, ref);
    return all.size() <= properties.getTreeMaxEntries()
        ? all : all.subList(0, properties.getTreeMaxEntries());
  }

  private List<String> planFilesToRead(final String issueKey, final String task,
      final List<String> tree) {
    final String user = "Tarea:\n" + task + "\n\nÁrbol del repo:\n" + String.join("\n", tree);
    final JsonNode root = parse(complete(PLAN_PROMPT, user, issueKey));
    if (root == null) {
      return List.of();
    }
    final JsonNode read = root.get("read");
    final List<String> requested = new ArrayList<>();
    if (read != null && read.isArray()) {
      for (final JsonNode node : read) {
        final String path = node.asText(null);
        if (path != null && !path.isBlank() && tree.contains(path)) {
          requested.add(path);
        }
        if (requested.size() >= properties.getMaxContextFiles()) {
          break;
        }
      }
    }
    return requested;
  }

  private Map<String, String> readContext(final String repo, final String ref,
      final List<String> paths) {
    final Map<String, String> context = new LinkedHashMap<>();
    for (final String path : paths) {
      final Optional<GithubClient.FileContent> file = githubClient.getFileContent(repo, ref, path);
      if (file.isPresent()) {
        context.put(path, truncate(file.get().content(), properties.getMaxFileChars()));
      }
    }
    return context;
  }

  private ChangeSet generateChanges(final String issueKey, final String task,
      final List<String> tree, final Map<String, String> context) {
    final StringBuilder user = new StringBuilder();
    user.append("Tarea:\n").append(task).append("\n\nÁrbol del repo:\n")
        .append(String.join("\n", tree));
    if (!context.isEmpty()) {
      user.append("\n\nContenido de ficheros relevantes:\n");
      for (final Map.Entry<String, String> entry : context.entrySet()) {
        user.append("=== ").append(entry.getKey()).append(" ===\n")
            .append(entry.getValue()).append("\n");
      }
    }
    return toChangeSet(complete(GENERATE_PROMPT, user.toString(), issueKey));
  }

  private ChangeSet toChangeSet(final String output) {
    final JsonNode root = parse(output);
    if (root == null) {
      return new ChangeSet("", List.of());
    }
    final List<FileChange> changes = new ArrayList<>();
    final JsonNode nodes = root.get("changes");
    if (nodes != null && nodes.isArray()) {
      for (final JsonNode node : nodes) {
        final String path = node.path("path").asText(null);
        final String content = node.path("content").asText(null);
        if (path == null || path.isBlank() || content == null) {
          continue;
        }
        final String action = node.path("action").asText("");
        changes.add(new FileChange(path.strip(), changeType(action), content));
        if (changes.size() >= properties.getMaxChanges()) {
          break;
        }
      }
    }
    return new ChangeSet(root.path("summary").asText(""), changes);
  }

  private FileChange.ChangeType changeType(final String action) {
    return "CREATE".equalsIgnoreCase(action)
        ? FileChange.ChangeType.CREATE : FileChange.ChangeType.UPDATE;
  }

  private void apply(final String repo, final String branch, final ChangeSet changeSet,
      final String issueKey) {
    for (final FileChange change : changeSet.changes()) {
      final Optional<GithubClient.FileContent> current =
          githubClient.getFileContent(repo, branch, change.path());
      final String sha = current.map(GithubClient.FileContent::sha).orElse(null);
      final String message = properties.getCommitMessagePrefix() + issueKey + " · " + change.path();
      githubClient.putFile(repo, branch, change.path(), change.content(), message, sha);
    }
  }

  private String complete(final String system, final String user, final String issueKey) {
    return llmClient.complete(LlmRequest.of(system, user, LlmRoles.CODER, issueKey));
  }

  private JsonNode parse(final String output) {
    if (output == null || output.isBlank()) {
      return null;
    }
    final int start = output.indexOf('{');
    final int end = output.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return null;
    }
    try {
      return objectMapper.readTree(output.substring(start, end + 1));
    } catch (JsonProcessingException e) {
      log.warn("Respuesta del coder no es JSON válido: {}", e.getMessage());
      return null;
    }
  }

  private static String task(final String summary, final String description) {
    return "Título: " + (summary == null ? "" : summary) + "\n\nDescripción:\n"
        + (description == null || description.isBlank() ? "(sin descripción)" : description);
  }

  private static String truncate(final String value, final int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(value.length() - max);
  }

  private static String paths(final ChangeSet changeSet) {
    final List<String> list = new ArrayList<>();
    for (final FileChange change : changeSet.changes()) {
      list.add(change.type() + " " + change.path());
    }
    return String.join(", ", list);
  }
}

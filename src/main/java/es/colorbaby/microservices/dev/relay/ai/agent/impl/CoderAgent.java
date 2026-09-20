package es.colorbaby.microservices.dev.relay.ai.agent.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.colorbaby.microservices.dev.relay.ai.agent.Agent;
import es.colorbaby.microservices.dev.relay.ai.agent.AgentContext;
import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentAction;
import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentMessage;
import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentResult;
import es.colorbaby.microservices.dev.relay.ai.agent.state.ActionType;
import es.colorbaby.microservices.dev.relay.ai.agent.state.AgentStatus;
import es.colorbaby.microservices.dev.relay.ai.knowledge.Knowledge;
import es.colorbaby.microservices.dev.relay.ai.knowledge.record.KnowledgeDocument;
import es.colorbaby.microservices.dev.relay.ai.knowledge.record.KnowledgeQuery;
import es.colorbaby.microservices.dev.relay.ai.skill.Skill;
import es.colorbaby.microservices.dev.relay.ai.skill.SkillRegistry;
import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolStatus;
import es.colorbaby.microservices.dev.relay.config.CoderProperties;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.guardrail.PromptShield;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import es.colorbaby.microservices.dev.relay.llm.LlmTruncatedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * El coder: genera el código real de la tarea dentro de la rama de la PR, en vez de un
 * placeholder. Reemplaza al antiguo {@code CoderService}: misma lógica en dos pasadas con el LLM
 * (rol {@code coder}) — primero decide qué ficheros existentes leer, y luego, con esos ficheros
 * como contexto, devuelve los cambios en JSON — pero ahora el acceso a GitHub es exclusivamente
 * vía tools ({@code github.list_paths}, {@code github.read_file}, {@code github.commit}), y el
 * commit pasa por el {@link es.colorbaby.microservices.dev.relay.ai.orchestration.AgentRuntime},
 * que le aplica {@link es.colorbaby.microservices.dev.relay.ai.tool.impl.guardrail.ChangeSetToolGuardrail}
 * de forma genérica.
 *
 * <p>Es una máquina de estados de varios pasos: cada llamada a {@link #execute} avanza un paso
 * (pedir el árbol → planificar lecturas → generar cambios y pedir el commit → cerrar) usando los
 * mensajes acumulados en el {@link AgentContext} para saber en qué paso está. El
 * {@code AgentRuntime} es quien ejecuta las tools pedidas y vuelve a llamar a {@link #execute}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoderAgent implements Agent {

  public static final String ID = "coder";

  private static final String GITHUB_LIST_PATHS = "github.list_paths";
  private static final String GITHUB_READ_FILE = "github.read_file";
  private static final String GITHUB_COMMIT = "github.commit";

  private static final String SKILL_PLAN = "coder-plan-reads";
  private static final String SKILL_GENERATE = "coder-generate-changes";

  private static final String STATE_TO_READ = "coder.toRead";
  private static final String STATE_SUMMARY = "coder.summary";
  private static final String STATE_CHANGED_FILES = "coder.changedFiles";

  private static final String SUCCESS_PREFIX = ToolStatus.SUCCESS.name() + ": ";

  private static final ChangeSet EMPTY_CHANGE_SET = new ChangeSet("", List.of());

  private static final String FALLBACK_PLAN_PROMPT =
      "Eres el coder de Sixai. Dime qué ficheros EXISTENTES del árbol necesitas leer. Responde "
      + "solo JSON: {\"read\": [\"ruta1\"]}.";
  private static final String FALLBACK_GENERATE_PROMPT =
      "Eres el coder de Sixai. Implementa la tarea. Responde solo JSON: {\"summary\": \"...\", "
      + "\"changes\": [{\"path\": \"...\", \"action\": \"CREATE|UPDATE|DELETE\", \"content\": "
      + "\"...\"}]}. Para DELETE omite \"content\" (o déjalo vacío): no hace falta.";

  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final CoderProperties properties;
  private final PromptShield promptShield;
  private final SkillRegistry skillRegistry;
  private final Knowledge knowledge;
  private final ObjectMapper objectMapper;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String description() {
    return "Implementa una tarea en un repositorio: lee el contexto que necesita y commitea "
        + "los cambios, todo a través de tools con guardarraíles.";
  }

  @Override
  public AgentResult execute(final AgentContext context) {
    if (!properties.isEnabled() || !llmProperties.isEnabled()) {
      return new AgentResult(AgentStatus.COMPLETED, "", List.of());
    }

    final Optional<AgentMessage> commitMessage = findMessage(context, GITHUB_COMMIT);
    if (commitMessage.isPresent()) {
      return finalizeFromCommit(context, commitMessage.get());
    }

    final Optional<AgentMessage> treeMessage = findMessage(context, GITHUB_LIST_PATHS);
    if (treeMessage.isEmpty()) {
      return requestTree(context);
    }

    final List<String> tree = parseTree(treeMessage.get());
    final List<String> toRead = readPlannedPaths(context);
    if (toRead == null) {
      return planReads(context, tree);
    }

    final Map<String, String> readContext = collectReadContext(context, toRead);
    return generateAndRequestCommit(context, tree, readContext);
  }

  private AgentResult requestTree(final AgentContext context) {
    final String repo = requireState(context, "repo");
    final String branch = requireState(context, "branch");
    final AgentAction action =
        new AgentAction(ActionType.TOOL, GITHUB_LIST_PATHS, Map.of("repo", repo, "ref", branch));
    return new AgentResult(AgentStatus.WAITING_FOR_TOOL, "Listando el árbol del repositorio",
        List.of(action));
  }

  private AgentResult planReads(final AgentContext context, final List<String> tree) {
    final String task = task(context);
    final String user = "Tarea:\n" + task + "\n\nÁrbol del repo:\n" + String.join("\n", tree);
    final String systemPrompt = skillPrompt(SKILL_PLAN, FALLBACK_PLAN_PROMPT);

    final List<String> requested = safeCall(() -> {
      final String output = llmClient.complete(
          LlmRequest.ofComplete(systemPrompt, user, LlmRoles.CODER, context.issueKey()));
      return parseReadList(output, tree);
    }, List.of(), context.issueKey());

    context.putState(STATE_TO_READ, requested);
    if (requested.isEmpty()) {
      return generateAndRequestCommit(context, tree, Map.of());
    }

    final String repo = requireState(context, "repo");
    final String branch = requireState(context, "branch");
    final List<AgentAction> actions = requested.stream()
        .map(path -> new AgentAction(ActionType.TOOL, GITHUB_READ_FILE,
            Map.of("repo", repo, "ref", branch, "path", path)))
        .toList();
    return new AgentResult(AgentStatus.WAITING_FOR_TOOL, "Leyendo ficheros de contexto", actions);
  }

  private AgentResult generateAndRequestCommit(
      final AgentContext context, final List<String> tree, final Map<String, String> readContext) {
    final String task = task(context);
    final ChangeSet changeSet = safeCall(
        () -> generateChanges(context, task, tree, readContext), EMPTY_CHANGE_SET, context.issueKey());

    final String repo = requireState(context, "repo");
    if (changeSet.isEmpty()) {
      log.info("El coder no propuso cambios aplicables para {} en {}", context.issueKey(), repo);
      return new AgentResult(AgentStatus.COMPLETED, "", List.of());
    }
    if (properties.isDryRun()) {
      log.info("[DRY-RUN] El coder cambiaría en {}: {}", repo, describePaths(changeSet));
      return new AgentResult(AgentStatus.COMPLETED, "", List.of());
    }

    final Map<String, String> files = new LinkedHashMap<>();
    final List<String> deletes = new ArrayList<>();
    for (final FileChange change : changeSet.changes()) {
      if (change.type() == FileChange.ChangeType.DELETE) {
        deletes.add(change.path());
      } else {
        files.put(change.path(), change.content());
      }
    }
    final String message = properties.getCommitMessagePrefix() + context.issueKey()
        + (changeSet.summary() == null || changeSet.summary().isBlank()
            ? "" : " · " + firstLine(changeSet.summary()));

    final List<String> changedPaths = new ArrayList<>(files.keySet());
    changedPaths.addAll(deletes);

    context.putState(STATE_SUMMARY, changeSet.summary());
    context.putState(STATE_CHANGED_FILES, changedPaths);
    final AgentAction commitAction = new AgentAction(ActionType.TOOL, GITHUB_COMMIT, Map.of(
        "repo", repo,
        "branch", requireState(context, "branch"),
        "files", files,
        "deletes", deletes,
        "message", message));
    return new AgentResult(AgentStatus.WAITING_FOR_TOOL, "Commiteando cambios", List.of(commitAction));
  }

  private AgentResult finalizeFromCommit(final AgentContext context, final AgentMessage message) {
    final boolean success = message.content() != null && message.content().startsWith(SUCCESS_PREFIX);
    if (!success) {
      log.warn("El commit del coder no se aplicó en {}: {}", context.issueKey(), message.content());
      return new AgentResult(AgentStatus.COMPLETED, "", List.of());
    }
    final Object summary = context.state().get(STATE_SUMMARY);
    final String text = summary == null || String.valueOf(summary).isBlank()
        ? "Cambios aplicados" : String.valueOf(summary);
    final Object changedFiles = context.state().getOrDefault(STATE_CHANGED_FILES, List.of());
    return new AgentResult(AgentStatus.COMPLETED, text, List.of(), Map.of("changedFiles", changedFiles));
  }

  // --- Generación con el LLM (misma lógica que el antiguo CoderService) ---

  private ChangeSet generateChanges(final AgentContext context, final String task,
      final List<String> tree, final Map<String, String> readContext) {
    final StringBuilder user = new StringBuilder();
    user.append("Tarea:\n").append(task).append("\n\nÁrbol del repo:\n")
        .append(String.join("\n", tree));
    appendPlan(context, user);
    if (!readContext.isEmpty()) {
      user.append("\n\nContenido de ficheros relevantes:\n");
      for (final Map.Entry<String, String> entry : readContext.entrySet()) {
        user.append("=== ").append(entry.getKey()).append(" ===\n")
            .append(entry.getValue()).append("\n");
      }
    }
    appendKnowledge(context, user);
    final String systemPrompt = skillPrompt(SKILL_GENERATE, FALLBACK_GENERATE_PROMPT);
    final String output = llmClient.complete(
        LlmRequest.ofComplete(systemPrompt, user.toString(), LlmRoles.CODER, context.issueKey()));
    return toChangeSet(output);
  }

  /**
   * Añade al prompt el plan del {@link PlannerAgent}, si lo hay (viene sembrado en el estado por
   * quien invocó al coder — ver {@code PullRequestService}). Sin planner activo, o si no dejó
   * plan, el coder decide como siempre.
   */
  private void appendPlan(final AgentContext context, final StringBuilder user) {
    final Object plan = context.state().get("plan");
    if (plan == null || String.valueOf(plan).isBlank()) {
      return;
    }
    user.append("\n\nPlan del planner (síguelo salvo que sea incompatible con lo que encuentres):\n")
        .append(plan);
  }

  /**
   * Añade al prompt la documentación de arquitectura relevante para la tarea, si el Knowledge
   * encuentra algo. Best-effort: si falla o no hay nada relevante, el coder sigue sin ese contexto
   * (igual que sin él antes de que existiera el Knowledge).
   */
  private void appendKnowledge(final AgentContext context, final StringBuilder user) {
    final String queryText = ((context.taskTitle() == null ? "" : context.taskTitle())
        + "\n" + (context.taskDescription() == null ? "" : context.taskDescription())).strip();
    if (queryText.isBlank()) {
      return;
    }
    final List<KnowledgeDocument> found;
    try {
      found = knowledge.retrieve(new KnowledgeQuery(queryText, context.issueKey(), 0)).documents();
    } catch (RuntimeException e) {
      log.warn("No se pudo consultar el Knowledge para {}: {}", context.issueKey(), e.getMessage());
      return;
    }
    if (found.isEmpty()) {
      return;
    }
    user.append("\n\nDocumentación de arquitectura relevante:\n");
    for (final KnowledgeDocument document : found) {
      user.append("=== ").append(document.title()).append(" (").append(document.source())
          .append(") ===\n").append(document.content()).append("\n");
    }
  }

  private ChangeSet toChangeSet(final String output) {
    final JsonNode root = parseJson(output);
    if (root == null) {
      return new ChangeSet("", List.of());
    }
    final List<FileChange> changes = new ArrayList<>();
    final JsonNode nodes = root.get("changes");
    if (nodes != null && nodes.isArray()) {
      for (final JsonNode node : nodes) {
        final String path = node.path("path").asText(null);
        if (path == null || path.isBlank()) {
          continue;
        }
        final FileChange.ChangeType type = changeType(node.path("action").asText(""));
        final String content = node.path("content").asText(null);
        // DELETE no necesita contenido; CREATE/UPDATE sin contenido no es un cambio aplicable.
        if (type != FileChange.ChangeType.DELETE && content == null) {
          continue;
        }
        changes.add(new FileChange(path.strip(), type, content == null ? "" : content));
        if (changes.size() >= properties.getMaxChanges()) {
          break;
        }
      }
    }
    return new ChangeSet(root.path("summary").asText(""), changes);
  }

  private FileChange.ChangeType changeType(final String action) {
    if ("CREATE".equalsIgnoreCase(action)) {
      return FileChange.ChangeType.CREATE;
    }
    if ("DELETE".equalsIgnoreCase(action)) {
      return FileChange.ChangeType.DELETE;
    }
    return FileChange.ChangeType.UPDATE;
  }

  private List<String> parseReadList(final String output, final List<String> tree) {
    final JsonNode root = parseJson(output);
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

  private JsonNode parseJson(final String output) {
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

  // --- Lectura del estado acumulado en el AgentContext ---

  private Optional<AgentMessage> findMessage(final AgentContext context, final String source) {
    final List<AgentMessage> messages = context.messages();
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i).source().equals(source)) {
        return Optional.of(messages.get(i));
      }
    }
    return Optional.empty();
  }

  @SuppressWarnings("unchecked")
  private List<String> readPlannedPaths(final AgentContext context) {
    return (List<String>) context.state().get(STATE_TO_READ);
  }

  private List<String> parseTree(final AgentMessage treeMessage) {
    final String text = unwrapSuccess(treeMessage);
    if (text == null || text.isBlank()) {
      return List.of();
    }
    final List<String> tree = Arrays.stream(text.split("\n"))
        .map(String::strip)
        .filter(line -> !line.isBlank())
        .toList();
    return tree.size() <= properties.getTreeMaxEntries()
        ? tree : tree.subList(0, properties.getTreeMaxEntries());
  }

  private Map<String, String> collectReadContext(final AgentContext context, final List<String> toRead) {
    final Map<String, String> result = new LinkedHashMap<>();
    for (final String path : toRead) {
      findMessage(context, GITHUB_READ_FILE + ":" + path)
          .map(this::unwrapSuccess)
          .filter(content -> content != null)
          .ifPresent(content -> result.put(path, truncate(content, properties.getMaxFileChars())));
    }
    return result;
  }

  private String unwrapSuccess(final AgentMessage message) {
    final String content = message.content();
    return content != null && content.startsWith(SUCCESS_PREFIX)
        ? content.substring(SUCCESS_PREFIX.length()) : null;
  }

  private String requireState(final AgentContext context, final String key) {
    final Object value = context.state().get(key);
    if (value == null) {
      throw new IllegalStateException("Falta '" + key + "' en el estado del agente coder");
    }
    return String.valueOf(value);
  }

  private String skillPrompt(final String skillId, final String fallback) {
    return skillRegistry.find(skillId).map(Skill::instructions).orElse(fallback);
  }

  /**
   * El título y la descripción los escribe una persona en Jira: son contenido externo, así que se
   * marcan como dato y no como instrucciones para el coder.
   */
  private String task(final AgentContext context) {
    final String summary = context.taskTitle();
    final String description = context.taskDescription();
    final String body = "Título: " + (summary == null ? "" : summary) + "\n\nDescripción:\n"
        + (description == null || description.isBlank() ? "(sin descripción)" : description);
    return promptShield.wrap("tarea de Jira", body);
  }

  private <T> T safeCall(final Supplier<T> supplier, final T fallback, final String issueKey) {
    try {
      return supplier.get();
    } catch (LlmTruncatedException e) {
      log.error("El coder se quedó sin tokens en {}: {}", issueKey, e.getMessage());
      return fallback;
    } catch (RuntimeException e) {
      log.warn("El coder falló en {}: {}", issueKey, e.getMessage());
      return fallback;
    }
  }

  /** El resumen del modelo puede venir en varias líneas; en el asunto del commit solo cabe una. */
  private static String firstLine(final String value) {
    final int newLine = value.indexOf('\n');
    final String line = (newLine < 0 ? value : value.substring(0, newLine)).strip();
    return line.length() <= 120 ? line : line.substring(0, 120);
  }

  /**
   * Recorta un fichero de contexto quedándose con el PRINCIPIO: es lo que más le dice al modelo
   * sobre un fichero de código (package, imports, declaración de la clase).
   */
  private static String truncate(final String value, final int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static String describePaths(final ChangeSet changeSet) {
    final List<String> list = new ArrayList<>();
    for (final FileChange change : changeSet.changes()) {
      list.add(change.type() + " " + change.path());
    }
    return String.join(", ", list);
  }
}

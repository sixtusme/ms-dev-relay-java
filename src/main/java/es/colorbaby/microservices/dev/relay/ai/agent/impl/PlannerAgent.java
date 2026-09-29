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
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.config.PlannerProperties;
import es.colorbaby.microservices.dev.relay.ai.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.ai.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.ai.llm.LlmRoles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * El planner: antes de que el {@link CoderAgent} escriba nada, entiende la tarea, inspecciona el
 * árbol del repo y decide un plan de implementación (qué tocar y cómo). Solo usa tools de
 * <b>lectura</b> — nunca escribe, igual que dice la arquitectura Maestro para este rol.
 *
 * <p>Máquina de estados de dos pasos: pide el árbol del repo, y con eso (más la documentación de
 * arquitectura relevante del {@link Knowledge}) genera el plan en una única llamada al LLM.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerAgent implements Agent {

  public static final String ID = "planner";

  private static final String GITHUB_LIST_PATHS = "github.list_paths";
  private static final String SKILL_PLAN = "planner-create-plan";
  private static final String SKILL_TRACK = "planner-classify-track";
  private static final String SKILL_ACCEPTANCE = "planner-check-acceptance";
  private static final String SUCCESS_PREFIX = ToolStatus.SUCCESS.name() + ": ";

  private static final String FALLBACK_PROMPT =
      "Eres el planner de Sixai. Decide cómo implementar la tarea (enfoque, ficheros a tocar, "
      + "decisiones clave), sin escribir código. El coder ejecutará tu plan después.";
  private static final String FALLBACK_TRACK_PROMPT =
      "Eres el planner de Sixai. A partir del título y la descripción de una tarea (todavía sin "
      + "mirar el repo), clasifica su complejidad y lista sus criterios de aceptación. Responde "
      + "SOLO JSON: {\"track\": \"SIMPLE|MODERATE|COMPLEX\", \"acceptanceCriteria\": [\"...\"], "
      + "\"rationale\": \"...\"}. SIMPLE: un cambio acotado y obvio (un texto, un valor de "
      + "configuración, un fix puntual). MODERATE: toca varios ficheros o requiere alguna decisión "
      + "de diseño. COMPLEX: afecta a varios sistemas, tiene ambigüedad real, o el riesgo de "
      + "hacerlo mal es alto. Los criterios de aceptación son frases cortas y comprobables ('esto "
      + "está bien hecho si...'); deja la lista vacía si la descripción no da para deducirlos con "
      + "confianza — no inventes requisitos que no están ahí.";
  private static final String FALLBACK_ACCEPTANCE_PROMPT =
      "Eres el planner de Sixai. Te doy los criterios de aceptación de una tarea y el resumen de "
      + "los cambios que se han entregado. Di si los cambios, tal y como se describen, parecen "
      + "cubrir los criterios. Responde SOLO JSON: {\"met\": true|false, \"explanation\": \"...\"}. "
      + "\"met\" es false solo si algún criterio claramente NO está cubierto por lo descrito; ante "
      + "la duda razonable, o si el resumen no basta para saberlo con certeza, responde true (no "
      + "avises de un problema que no puedes confirmar). \"explanation\" solo hace falta si "
      + "\"met\" es false: qué criterio parece faltar, en pocas frases.";

  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final PlannerProperties properties;
  private final SkillRegistry skillRegistry;
  private final Knowledge knowledge;
  private final ObjectMapper objectMapper;

  /** Complejidad de una tarea, para decidir cuánto rigor de proceso le hace falta. */
  public enum TaskTrack {
    SIMPLE, MODERATE, COMPLEX
  }

  /**
   * @param acceptanceCriteria frases cortas y comprobables; vacía si no se pudieron deducir con
   *                           confianza de la descripción
   * @param rationale          por qué ese track, en una frase; puede quedar vacío
   */
  public record TrackClassification(
      TaskTrack track, List<String> acceptanceCriteria, String rationale) {
  }

  /**
   * @param met         si los cambios entregados parecen cubrir los criterios; por defecto
   *                    {@code true} (ante la duda, o sin poder comprobarlo, no se avisa de nada)
   * @param explanation qué criterio parece faltar, solo si {@code met} es {@code false}
   */
  public record AcceptanceCheck(boolean met, String explanation) {
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String description() {
    return "Entiende la tarea e inspecciona el repo para decidir un plan de implementación, "
        + "antes de que el coder escriba nada. Solo lectura.";
  }

  @Override
  public AgentResult execute(final AgentContext context) {
    if (!properties.isEnabled() || !llmProperties.isEnabled()) {
      return new AgentResult(AgentStatus.COMPLETED, "", List.of());
    }

    final Optional<AgentMessage> treeMessage = findMessage(context, GITHUB_LIST_PATHS);
    if (treeMessage.isEmpty()) {
      final String repo = requireState(context, "repo");
      final String branch = requireState(context, "branch");
      final AgentAction action = new AgentAction(ActionType.TOOL, GITHUB_LIST_PATHS,
          Map.of("repo", repo, "ref", branch));
      return new AgentResult(AgentStatus.WAITING_FOR_TOOL, "Listando el árbol del repositorio",
          List.of(action));
    }

    final List<String> tree = parseTree(treeMessage.get());
    final String plan = createPlan(context, tree);
    return new AgentResult(AgentStatus.COMPLETED, plan, List.of());
  }

  /**
   * Clasifica la complejidad de una tarea y extrae sus criterios de aceptación, a partir del
   * título y la descripción — todavía sin árbol de repo, es anterior a elegir qué tocar. Llamada
   * directa (no pasa por el bucle de tools del {@code AgentRuntime}, igual que
   * {@code classifyVerificationFailure} de {@link DiagnosticianAgent}). Best-effort: nunca lanza;
   * {@code MODERATE} sin criterios es el valor por defecto si no hay IA o el modelo no responde
   * JSON válido — es la opción intermedia, ni la más laxa ni la más exigente.
   */
  public TrackClassification classifyTrack(
      final String issueKey, final String title, final String description) {
    if (!properties.isEnabled() || !llmProperties.isEnabled()) {
      return new TrackClassification(TaskTrack.MODERATE, List.of(), "");
    }
    try {
      final String user = "Título: " + nullSafe(title) + "\n\nDescripción:\n" + nullSafe(description);
      final String systemPrompt = skillRegistry.find(SKILL_TRACK)
          .map(Skill::instructions).orElse(FALLBACK_TRACK_PROMPT);
      final String output =
          llmClient.complete(LlmRequest.of(systemPrompt, user, LlmRoles.PLANNER, issueKey));
      return parseTrack(output);
    } catch (RuntimeException e) {
      log.warn("No se pudo clasificar el track de {}: {}", issueKey, e.getMessage());
      return new TrackClassification(TaskTrack.MODERATE, List.of(), "");
    }
  }

  private TrackClassification parseTrack(final String output) {
    if (output == null || output.isBlank()) {
      return new TrackClassification(TaskTrack.MODERATE, List.of(), "");
    }
    final int start = output.indexOf('{');
    final int end = output.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return new TrackClassification(TaskTrack.MODERATE, List.of(), "");
    }
    try {
      final JsonNode root = objectMapper.readTree(output.substring(start, end + 1));
      final List<String> criteria = new ArrayList<>();
      final JsonNode criteriaNode = root.get("acceptanceCriteria");
      if (criteriaNode != null && criteriaNode.isArray()) {
        for (final JsonNode node : criteriaNode) {
          final String text = node.asText(null);
          if (text != null && !text.isBlank()) {
            criteria.add(text.strip());
          }
        }
      }
      return new TrackClassification(
          trackOf(root.path("track").asText("")), criteria, root.path("rationale").asText(""));
    } catch (JsonProcessingException e) {
      log.warn("Clasificación de track no es JSON válido: {}", e.getMessage());
      return new TrackClassification(TaskTrack.MODERATE, List.of(), "");
    }
  }

  /**
   * Comprueba si lo que el coder dice haber entregado cubre los criterios de aceptación de PLAN
   * (mejoras-senior Fase 6). Puramente informativo: nunca bloquea nada, solo da pie a un aviso en
   * la tarea — la verificación real sigue siendo que el código compile. Best-effort: sin IA, o si
   * el modelo no responde JSON válido, {@code met=true} es el valor por defecto (no se avisa de un
   * problema que no se puede confirmar).
   */
  public AcceptanceCheck checkAcceptance(
      final String issueKey, final String criteria, final String changesSummary) {
    if (!properties.isEnabled() || !llmProperties.isEnabled()) {
      return new AcceptanceCheck(true, "");
    }
    try {
      final String user = criteria + "\n\nCambios entregados:\n" + changesSummary;
      final String systemPrompt = skillRegistry.find(SKILL_ACCEPTANCE)
          .map(Skill::instructions).orElse(FALLBACK_ACCEPTANCE_PROMPT);
      final String output =
          llmClient.complete(LlmRequest.of(systemPrompt, user, LlmRoles.PLANNER, issueKey));
      return parseAcceptance(output);
    } catch (RuntimeException e) {
      log.warn("No se pudo comprobar los criterios de aceptación de {}: {}", issueKey, e.getMessage());
      return new AcceptanceCheck(true, "");
    }
  }

  private AcceptanceCheck parseAcceptance(final String output) {
    if (output == null || output.isBlank()) {
      return new AcceptanceCheck(true, "");
    }
    final int start = output.indexOf('{');
    final int end = output.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return new AcceptanceCheck(true, "");
    }
    try {
      final JsonNode root = objectMapper.readTree(output.substring(start, end + 1));
      final boolean met = root.path("met").asBoolean(true);
      return new AcceptanceCheck(met, met ? "" : root.path("explanation").asText(""));
    } catch (JsonProcessingException e) {
      log.warn("Comprobación de criterios no es JSON válido: {}", e.getMessage());
      return new AcceptanceCheck(true, "");
    }
  }

  private static TaskTrack trackOf(final String value) {
    try {
      return TaskTrack.valueOf(value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return TaskTrack.MODERATE;
    }
  }

  private String createPlan(final AgentContext context, final List<String> tree) {
    final StringBuilder user = new StringBuilder();
    user.append("Tarea:\nTítulo: ").append(nullSafe(context.taskTitle()))
        .append("\nDescripción:\n").append(nullSafe(context.taskDescription()))
        .append("\n\nÁrbol del repo:\n").append(String.join("\n", tree));
    appendKnowledge(context, user);

    final String systemPrompt = skillRegistry.find(SKILL_PLAN)
        .map(Skill::instructions).orElse(FALLBACK_PROMPT);
    try {
      return llmClient.complete(
          LlmRequest.of(systemPrompt, user.toString(), LlmRoles.PLANNER, context.issueKey()));
    } catch (RuntimeException e) {
      log.warn("El planner falló en {}: {}", context.issueKey(), e.getMessage());
      return "";
    }
  }

  private void appendKnowledge(final AgentContext context, final StringBuilder user) {
    final String queryText = (nullSafe(context.taskTitle()) + "\n" + nullSafe(context.taskDescription())).strip();
    if (queryText.isBlank()) {
      return;
    }
    try {
      final List<KnowledgeDocument> found =
          knowledge.retrieve(new KnowledgeQuery(queryText, context.issueKey(), 0)).documents();
      if (found.isEmpty()) {
        return;
      }
      user.append("\n\nDocumentación de arquitectura relevante:\n");
      for (final KnowledgeDocument document : found) {
        user.append("=== ").append(document.title()).append(" ===\n")
            .append(document.content()).append("\n");
      }
    } catch (RuntimeException e) {
      log.warn("No se pudo consultar el Knowledge para {}: {}", context.issueKey(), e.getMessage());
    }
  }

  private Optional<AgentMessage> findMessage(final AgentContext context, final String source) {
    final List<AgentMessage> messages = context.messages();
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i).source().equals(source)) {
        return Optional.of(messages.get(i));
      }
    }
    return Optional.empty();
  }

  private List<String> parseTree(final AgentMessage treeMessage) {
    final String content = treeMessage.content();
    if (content == null || !content.startsWith(SUCCESS_PREFIX)) {
      return List.of();
    }
    final String text = content.substring(SUCCESS_PREFIX.length());
    if (text.isBlank()) {
      return List.of();
    }
    final List<String> tree = Arrays.stream(text.split("\n"))
        .map(String::strip)
        .filter(line -> !line.isBlank())
        .toList();
    return tree.size() <= properties.getTreeMaxEntries()
        ? tree : tree.subList(0, properties.getTreeMaxEntries());
  }

  private String requireState(final AgentContext context, final String key) {
    final Object value = context.state().get(key);
    if (value == null) {
      throw new IllegalStateException("Falta '" + key + "' en el estado del agente planner");
    }
    return String.valueOf(value);
  }

  private static String nullSafe(final String value) {
    return value == null ? "" : value;
  }
}

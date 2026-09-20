package es.colorbaby.microservices.dev.relay.ai.agent.impl;

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
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import java.util.Arrays;
import java.util.List;
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
  private static final String SUCCESS_PREFIX = ToolStatus.SUCCESS.name() + ": ";

  private static final String FALLBACK_PROMPT =
      "Eres el planner de Sixai. Decide cómo implementar la tarea (enfoque, ficheros a tocar, "
      + "decisiones clave), sin escribir código. El coder ejecutará tu plan después.";

  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final PlannerProperties properties;
  private final SkillRegistry skillRegistry;
  private final Knowledge knowledge;

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

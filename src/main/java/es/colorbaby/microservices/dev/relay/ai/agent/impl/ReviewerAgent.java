package es.colorbaby.microservices.dev.relay.ai.agent.impl;

import es.colorbaby.microservices.dev.relay.ai.agent.Agent;
import es.colorbaby.microservices.dev.relay.ai.agent.AgentContext;
import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentAction;
import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentMessage;
import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentResult;
import es.colorbaby.microservices.dev.relay.ai.agent.state.ActionType;
import es.colorbaby.microservices.dev.relay.ai.agent.state.AgentStatus;
import es.colorbaby.microservices.dev.relay.ai.skill.Skill;
import es.colorbaby.microservices.dev.relay.ai.skill.SkillRegistry;
import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolStatus;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.config.ReviewerProperties;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * El reviewer: lee (nunca modifica) los ficheros que el {@link CoderAgent} acaba de commitear y da
 * un veredicto de calidad. Su opinión se comenta en la tarea junto al enlace de la PR — nunca
 * bloquea ni autoriza nada; eso sigue dependiendo solo de la identidad de quien aprueba.
 *
 * <p>No decide QUÉ leer (eso ya lo sabe: la lista de ficheros que cambió el coder, que le llega por
 * parámetro), así que es una máquina de estados de dos pasos: pide leer esos ficheros, y con su
 * contenido genera el veredicto en una única llamada al LLM.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewerAgent implements Agent {

  public static final String ID = "reviewer";

  private static final String GITHUB_READ_FILE = "github.read_file";
  private static final String SKILL_REVIEW = "reviewer-review-changes";
  private static final String SUCCESS_PREFIX = ToolStatus.SUCCESS.name() + ": ";

  private static final String FALLBACK_PROMPT =
      "Eres el reviewer de Sixai. Da un veredicto breve de calidad sobre los cambios, sin "
      + "modificarlos. Esto es una opinión para quien apruebe, nunca una autorización.";

  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final ReviewerProperties properties;
  private final SkillRegistry skillRegistry;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String description() {
    return "Revisa los ficheros que el coder acaba de commitear y da un veredicto de calidad. "
        + "Solo lectura: nunca modifica código ni bloquea nada.";
  }

  @Override
  public AgentResult execute(final AgentContext context) {
    if (!properties.isEnabled() || !llmProperties.isEnabled()) {
      return new AgentResult(AgentStatus.COMPLETED, "", List.of());
    }
    final Object rawFiles = context.state().get("changedFiles");
    final List<String> changedFiles = rawFiles instanceof List<?> list
        ? list.stream().map(String::valueOf).toList() : List.of();
    if (changedFiles.isEmpty()) {
      return new AgentResult(AgentStatus.COMPLETED, "", List.of());
    }
    final List<String> toReview = changedFiles.size() > properties.getMaxFiles()
        ? changedFiles.subList(0, properties.getMaxFiles()) : changedFiles;

    if (!alreadyRequestedReads(context, toReview)) {
      final String repo = requireState(context, "repo");
      final String branch = requireState(context, "branch");
      final List<AgentAction> actions = toReview.stream()
          .map(path -> new AgentAction(ActionType.TOOL, GITHUB_READ_FILE,
              Map.of("repo", repo, "ref", branch, "path", path)))
          .toList();
      return new AgentResult(AgentStatus.WAITING_FOR_TOOL, "Leyendo los cambios a revisar", actions);
    }

    final String verdict = review(context, toReview);
    return new AgentResult(AgentStatus.COMPLETED, verdict, List.of());
  }

  private boolean alreadyRequestedReads(final AgentContext context, final List<String> paths) {
    return paths.stream().anyMatch(path -> findMessage(context, GITHUB_READ_FILE + ":" + path).isPresent());
  }

  private String review(final AgentContext context, final List<String> paths) {
    final Map<String, String> contents = new LinkedHashMap<>();
    for (final String path : paths) {
      findMessage(context, GITHUB_READ_FILE + ":" + path)
          .map(this::unwrapSuccess)
          .filter(content -> content != null)
          .ifPresent(content -> contents.put(path, truncate(content, properties.getMaxFileChars())));
    }

    final StringBuilder user = new StringBuilder();
    user.append("Tarea:\nTítulo: ").append(nullSafe(context.taskTitle()))
        .append("\nDescripción:\n").append(nullSafe(context.taskDescription()));
    final Object codeSummary = context.state().get("codeSummary");
    if (codeSummary != null && !String.valueOf(codeSummary).isBlank()) {
      user.append("\n\nResumen del coder:\n").append(codeSummary);
    }
    if (contents.isEmpty()) {
      user.append("\n\n(No se pudo leer ningún fichero de los cambiados.)");
    } else {
      user.append("\n\nContenido actual de los ficheros cambiados:\n");
      contents.forEach((path, content) -> user.append("=== ").append(path).append(" ===\n")
          .append(content).append("\n"));
    }

    final String systemPrompt = skillRegistry.find(SKILL_REVIEW)
        .map(Skill::instructions).orElse(FALLBACK_PROMPT);
    try {
      return llmClient.complete(
          LlmRequest.of(systemPrompt, user.toString(), LlmRoles.REVIEWER, context.issueKey()));
    } catch (RuntimeException e) {
      log.warn("El reviewer falló en {}: {}", context.issueKey(), e.getMessage());
      return "";
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

  private String unwrapSuccess(final AgentMessage message) {
    final String content = message.content();
    return content != null && content.startsWith(SUCCESS_PREFIX)
        ? content.substring(SUCCESS_PREFIX.length()) : null;
  }

  private String requireState(final AgentContext context, final String key) {
    final Object value = context.state().get(key);
    if (value == null) {
      throw new IllegalStateException("Falta '" + key + "' en el estado del agente reviewer");
    }
    return String.valueOf(value);
  }

  private static String truncate(final String value, final int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static String nullSafe(final String value) {
    return value == null ? "" : value;
  }
}

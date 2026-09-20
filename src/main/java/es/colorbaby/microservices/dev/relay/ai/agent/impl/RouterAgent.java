package es.colorbaby.microservices.dev.relay.ai.agent.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.colorbaby.microservices.dev.relay.ai.agent.Agent;
import es.colorbaby.microservices.dev.relay.ai.agent.AgentContext;
import es.colorbaby.microservices.dev.relay.ai.agent.DefaultAgentContext;
import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentResult;
import es.colorbaby.microservices.dev.relay.ai.agent.state.AgentStatus;
import es.colorbaby.microservices.dev.relay.ai.knowledge.record.KnowledgeContext;
import es.colorbaby.microservices.dev.relay.ai.skill.Skill;
import es.colorbaby.microservices.dev.relay.ai.skill.SkillRegistry;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.control.command.CommandIntent;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Clasifica la orden en texto libre de un comentario {@code /sixai} en una intención de la lista
 * cerrada {@link CommandIntent}. Reemplaza al antiguo {@code CommandIntentInterpreter}: misma
 * lógica (LLM con fallback determinista por palabras clave), ahora como {@link Agent} de la
 * arquitectura Maestro.
 *
 * <p>La salida SIEMPRE se valida contra el enum: si no casa, es {@link CommandIntent#UNKNOWN} y
 * sixai pedirá aclaración en vez de actuar. El modelo nunca elige una acción fuera del catálogo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouterAgent implements Agent {

  private static final String SKILL_ID = "command-routing";

  // Fallback si, por lo que sea, la skill no está en el classpath.
  private static final String FALLBACK_SYSTEM_PROMPT =
      "Eres Sixai. Clasifica la orden en UNA de: PROMOTE_TO_PROD, REVISE, REDEPLOY, STATUS, "
      + "CANCEL, UNKNOWN. Responde ÚNICAMENTE con JSON: {\"intent\": \"...\", \"detail\": \"...\"}.";

  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final ObjectMapper objectMapper;
  private final SkillRegistry skillRegistry;

  @Override
  public String id() {
    return "router";
  }

  @Override
  public String description() {
    return "Clasifica una orden de un comentario /sixai en una intención de catálogo cerrado.";
  }

  /** Intención de una orden. Nunca null; ante la duda, {@link CommandIntent#UNKNOWN}. */
  public CommandIntent route(final String issueKey, final String issueStatus, final String instruction) {
    final AgentResult result = execute(newContext(issueKey, issueStatus, instruction));
    return CommandIntent.valueOf(result.message());
  }

  @Override
  public AgentResult execute(final AgentContext context) {
    final String instruction = context.taskDescription();
    final String issueStatus =
        String.valueOf(context.state().getOrDefault("issueStatus", "(desconocido)"));

    CommandIntent intent = null;
    if (llmProperties.isEnabled()) {
      intent = classifyWithLlm(context.issueKey(), issueStatus, instruction);
      if (intent == null) {
        log.warn("El LLM no clasificó el comando de {}; uso el fallback por keywords",
            context.issueKey());
      }
    }
    if (intent == null) {
      intent = classifyWithKeywords(instruction);
    }
    return new AgentResult(AgentStatus.COMPLETED, intent.name(), List.of());
  }

  private CommandIntent classifyWithLlm(
      final String issueKey, final String issueStatus, final String instruction) {
    final String systemPrompt = skillRegistry.find(SKILL_ID)
        .map(Skill::instructions)
        .orElse(FALLBACK_SYSTEM_PROMPT);
    final String userPrompt = "Estado de la tarea: " + issueStatus + "\n\nOrden:\n" + instruction;
    final String output;
    try {
      output = llmClient.complete(LlmRequest.of(systemPrompt, userPrompt, LlmRoles.ROUTER, issueKey));
    } catch (RuntimeException e) {
      log.warn("Fallo del LLM interpretando el comando de {}: {}", issueKey, e.getMessage());
      return null;
    }
    return parseIntent(output);
  }

  private CommandIntent parseIntent(final String output) {
    if (output == null || output.isBlank()) {
      return null;
    }
    final int start = output.indexOf('{');
    final int end = output.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return null;
    }
    final JsonNode root;
    try {
      root = objectMapper.readTree(output.substring(start, end + 1));
    } catch (JsonProcessingException e) {
      log.warn("Respuesta del router no es JSON válido: {}", e.getMessage());
      return null;
    }
    final String value = root.path("intent").asText("");
    for (final CommandIntent intent : CommandIntent.values()) {
      if (intent.name().equalsIgnoreCase(value.strip())) {
        return intent;
      }
    }
    return null;
  }

  // Fallback sin IA: palabras clave sobre la orden.
  private CommandIntent classifyWithKeywords(final String instruction) {
    final String text = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
    if (text.isBlank()) {
      return CommandIntent.UNKNOWN;
    }
    if (contains(text, "prod", "producción", "produccion", "promociona", "publica")) {
      return CommandIntent.PROMOTE_TO_PROD;
    }
    if (contains(text, "cancela", "aborta", "para", "detén", "deten")) {
      return CommandIntent.CANCEL;
    }
    if (contains(text, "cómo va", "como va", "estado", "status", "qué falta", "que falta")) {
      return CommandIntent.STATUS;
    }
    if (contains(text, "redespliega", "vuelve a desplegar", "redeploy")) {
      return CommandIntent.REDEPLOY;
    }
    if (contains(text, "cambia", "corrige", "arregla", "añade", "anade", "quita", "está mal",
        "esta mal", "revisa")) {
      return CommandIntent.REVISE;
    }
    return CommandIntent.UNKNOWN;
  }

  private static boolean contains(final String text, final String... needles) {
    for (final String needle : needles) {
      if (text.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private AgentContext newContext(
      final String issueKey, final String issueStatus, final String instruction) {
    final AgentContext context = new DefaultAgentContext(
        UUID.randomUUID().toString(),
        issueKey,
        "Comando /sixai",
        instruction,
        List.of(),
        new KnowledgeContext(List.of(), List.of()),
        List.of());
    context.putState("issueStatus", issueStatus);
    return context;
  }
}

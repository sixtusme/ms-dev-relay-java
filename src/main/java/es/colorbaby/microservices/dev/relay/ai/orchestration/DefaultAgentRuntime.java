package es.colorbaby.microservices.dev.relay.ai.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.colorbaby.microservices.dev.relay.ai.agent.Agent;
import es.colorbaby.microservices.dev.relay.ai.agent.AgentContext;
import es.colorbaby.microservices.dev.relay.ai.agent.DefaultAgentContext;
import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentAction;
import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentMessage;
import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentResult;
import es.colorbaby.microservices.dev.relay.ai.agent.state.ActionType;
import es.colorbaby.microservices.dev.relay.ai.agent.state.AgentStatus;
import es.colorbaby.microservices.dev.relay.ai.agent.state.MessageRole;
import es.colorbaby.microservices.dev.relay.ai.knowledge.record.KnowledgeContext;
import es.colorbaby.microservices.dev.relay.ai.orchestration.record.AgentExecutionRequest;
import es.colorbaby.microservices.dev.relay.ai.orchestration.record.AgentExecutionResult;
import es.colorbaby.microservices.dev.relay.ai.tool.Tool;
import es.colorbaby.microservices.dev.relay.ai.tool.ToolGuardrail;
import es.colorbaby.microservices.dev.relay.ai.tool.ToolRegistry;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Implementación del loop LLM → tool → LLM descrito en la arquitectura Maestro: hace avanzar a un
 * {@link Agent} paso a paso, ejecutando las tools que pida (si están autorizadas para él) y
 * devolviéndole el resultado, hasta que termine, falle, pida entrada humana o delegue en otro
 * agente. Cada {@link Agent} sigue haciendo su propia llamada al LLM dentro de
 * {@link Agent#execute}; este runtime NUNCA llama al LLM directamente, solo orquesta.
 *
 * <p>Cuando un agente pide entrada humana ({@code NEEDS_INPUT}), el {@link AgentContext} (mensajes
 * y estado acumulados) se guarda como {@link AgentSuspension} antes de devolver el resultado:
 * {@link #resume} lo recupera tal cual estaba y sigue el mismo bucle, en vez de reiniciar al
 * agente desde cero (mejoras-senior §8.2).
 */
@Slf4j
@Component
public class DefaultAgentRuntime implements AgentRuntime {

  private static final int MAX_STEPS = 8;

  private final Map<String, Agent> agentsById;
  private final ToolRegistry toolRegistry;
  private final List<ToolGuardrail> guardrails;
  private final AgentSuspensionRepository suspensions;
  private final ObjectMapper objectMapper;

  public DefaultAgentRuntime(final List<Agent> agents, final ToolRegistry toolRegistry,
      final List<ToolGuardrail> guardrails, final AgentSuspensionRepository suspensions,
      final ObjectMapper objectMapper) {
    this.agentsById = agents.stream()
        .collect(Collectors.toUnmodifiableMap(Agent::id, Function.identity()));
    this.toolRegistry = toolRegistry;
    this.guardrails = List.copyOf(guardrails);
    this.suspensions = suspensions;
    this.objectMapper = objectMapper;
  }

  @Override
  public AgentExecutionResult execute(final AgentExecutionRequest request) {
    final Agent agent = resolve(request.initialAgent());
    final String executionId = UUID.randomUUID().toString();
    final AgentContext context = new DefaultAgentContext(
        executionId,
        request.issueKey(),
        request.title(),
        request.description(),
        List.of(),
        new KnowledgeContext(List.of(), List.of()),
        toolRegistry.findForAgent(agent.id()));
    request.parameters().forEach(context::putState);

    log.info("Ejecución {} iniciada con el agente {} para {}",
        executionId, agent.id(), request.issueKey());
    return run(agent, context, MAX_STEPS, repoOf(request.parameters()));
  }

  @Override
  public Optional<AgentExecutionResult> resume(final String issueKey, final String repo,
      final String agentId, final String answer) {
    return suspensions.findByIssueKeyAndRepoAndAgentId(issueKey, repo, agentId).map(suspension -> {
      final Agent agent = resolve(agentId);
      final AgentContext context = new DefaultAgentContext(
          suspension.getExecutionId(),
          issueKey,
          suspension.getTaskTitle(),
          suspension.getTaskDescription(),
          List.of(),
          new KnowledgeContext(List.of(), List.of()),
          toolRegistry.findForAgent(agent.id()));
      readMessages(suspension.getMessages()).forEach(context::addMessage);
      readState(suspension.getState()).forEach(context::putState);
      context.addMessage(new AgentMessage(MessageRole.USER, "human.answer", answer));
      suspensions.delete(suspension);
      log.info("Ejecución {} del agente {} reanudada para {} ({}), con la respuesta de una persona",
          suspension.getExecutionId(), agentId, issueKey, repo);
      return run(agent, context, MAX_STEPS, repo);
    });
  }

  private AgentExecutionResult run(final Agent agent, final AgentContext context,
      final int stepsLeft, final String repo) {
    if (stepsLeft <= 0) {
      log.warn("Ejecución {} del agente {} alcanzó el límite de pasos", context.executionId(), agent.id());
      return new AgentExecutionResult(context.executionId(), AgentStatus.FAILED,
          "Se alcanzó el límite de pasos sin completar la tarea");
    }

    final AgentResult result = agent.execute(context);

    return switch (result.status()) {
      case COMPLETED, FAILED ->
          new AgentExecutionResult(context.executionId(), result.status(), result.message(), result.data());
      case NEEDS_INPUT -> {
        // El mensaje queda en el propio contexto ANTES de guardarlo: así, al reanudar, el agente ve
        // "esto pregunté, esto contestaron" en vez de solo la respuesta suelta.
        context.addMessage(new AgentMessage(MessageRole.AGENT, "agent.question", result.message()));
        saveSuspension(agent.id(), context, repo, result.message());
        yield new AgentExecutionResult(context.executionId(), result.status(), result.message(), result.data());
      }
      case WAITING_FOR_TOOL -> {
        applyToolActions(agent, context, result.actions());
        yield run(agent, context, stepsLeft - 1, repo);
      }
      case WAITING_FOR_AGENT -> run(resolveDelegate(result.actions()), context, stepsLeft - 1, repo);
      case CONTINUE -> run(agent, context, stepsLeft - 1, repo);
    };
  }

  /** Una sola ejecución pendiente por tarea/repo/agente: una pregunta nueva sustituye a la anterior. */
  private void saveSuspension(final String agentId, final AgentContext context, final String repo,
      final String question) {
    try {
      final String messagesJson = objectMapper.writeValueAsString(context.messages());
      final String stateJson = objectMapper.writeValueAsString(context.state());
      suspensions.deleteByIssueKeyAndRepoAndAgentId(context.issueKey(), repo, agentId);
      suspensions.save(new AgentSuspension(context.executionId(), agentId, context.issueKey(), repo,
          context.taskTitle(), context.taskDescription(), question, messagesJson, stateJson));
    } catch (JsonProcessingException e) {
      // Sin la ejecución guardada, la pregunta se pierde y la tarea se queda sin forma de
      // reanudarse; es mejor que quede en el log, ya que no hay nada más que hacer aquí.
      log.error("No se pudo guardar la ejecución {} del agente {} para poder reanudarla: {}",
          context.executionId(), agentId, e.getMessage());
    }
  }

  private List<AgentMessage> readMessages(final String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<List<AgentMessage>>() {
      });
    } catch (JsonProcessingException e) {
      log.warn("No se pudieron leer los mensajes de una ejecución guardada: {}", e.getMessage());
      return List.of();
    }
  }

  private Map<String, Object> readState(final String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
      });
    } catch (JsonProcessingException e) {
      log.warn("No se pudo leer el estado de una ejecución guardada: {}", e.getMessage());
      return Map.of();
    }
  }

  private static String repoOf(final Map<String, Object> parameters) {
    final Object repo = parameters.get("repo");
    return repo == null ? "" : String.valueOf(repo);
  }

  private void applyToolActions(final Agent agent, final AgentContext context,
      final List<AgentAction> actions) {
    for (final AgentAction action : actions) {
      if (action.type() != ActionType.TOOL) {
        continue;
      }
      final ToolResult toolResult = executeTool(agent, context, action);
      context.addMessage(new AgentMessage(MessageRole.TOOL, messageSource(action), describe(toolResult)));
    }
  }

  private ToolResult executeTool(final Agent agent, final AgentContext context, final AgentAction action) {
    final boolean permitted = context.availableTools().stream()
        .anyMatch(tool -> tool.name().equals(action.target()));
    if (!permitted) {
      log.warn("Agente {} intentó usar una tool no autorizada: {}", agent.id(), action.target());
      return ToolResult.denied("Tool no autorizada para este agente: " + action.target());
    }

    final Tool tool = toolRegistry.find(action.target()).orElse(null);
    if (tool == null) {
      return ToolResult.failure("Tool no encontrada: " + action.target());
    }

    final ToolContext toolContext =
        new ToolContext(context.executionId(), context.issueKey(), agent.id(), context);
    ToolArguments arguments = new ToolArguments(action.arguments());

    for (final ToolGuardrail guardrail : guardrails) {
      if (!guardrail.appliesTo(tool)) {
        continue;
      }
      final ToolGuardrail.Outcome outcome = guardrail.check(toolContext, arguments);
      if (outcome.denied()) {
        log.warn("Guardarraíl {} denegó la ejecución de {}: {}",
            guardrail.getClass().getSimpleName(), tool.name(), outcome.denial().content());
        return outcome.denial();
      }
      arguments = outcome.arguments();
    }

    try {
      return tool.execute(toolContext, arguments);
    } catch (Exception e) {
      log.warn("Fallo ejecutando la tool {}", tool.name(), e);
      return ToolResult.failure("Error ejecutando " + tool.name() + ": " + e.getMessage());
    }
  }

  /**
   * Identifica de qué llamada concreta viene un resultado, no solo de qué tool: si un agente pide
   * la misma tool varias veces en un paso (ej. leer N ficheros), el {@code target} solo no basta
   * para que el agente sepa, al leer los mensajes en el siguiente paso, cuál era cuál.
   */
  private String messageSource(final AgentAction action) {
    final Object path = action.arguments().get("path");
    return path == null ? action.target() : action.target() + ":" + path;
  }

  private String describe(final ToolResult result) {
    return result.status() + ": " + result.content();
  }

  private Agent resolve(final String agentId) {
    final Agent agent = agentsById.get(agentId);
    if (agent == null) {
      throw new IllegalArgumentException("Agente desconocido: " + agentId);
    }
    return agent;
  }

  private Agent resolveDelegate(final List<AgentAction> actions) {
    return actions.stream()
        .filter(action -> action.type() == ActionType.AGENT)
        .findFirst()
        .map(action -> resolve(action.target()))
        .orElseThrow(() -> new IllegalStateException(
            "Estado WAITING_FOR_AGENT sin ninguna acción de tipo AGENT"));
  }
}

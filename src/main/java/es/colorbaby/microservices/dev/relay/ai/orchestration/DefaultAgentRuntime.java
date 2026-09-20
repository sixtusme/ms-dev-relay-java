package es.colorbaby.microservices.dev.relay.ai.orchestration;

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
import es.colorbaby.microservices.dev.relay.ai.tool.ToolRegistry;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import java.util.List;
import java.util.Map;
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
 */
@Slf4j
@Component
public class DefaultAgentRuntime implements AgentRuntime {

  private static final int MAX_STEPS = 8;

  private final Map<String, Agent> agentsById;
  private final ToolRegistry toolRegistry;

  public DefaultAgentRuntime(final List<Agent> agents, final ToolRegistry toolRegistry) {
    this.agentsById = agents.stream()
        .collect(Collectors.toUnmodifiableMap(Agent::id, Function.identity()));
    this.toolRegistry = toolRegistry;
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

    log.info("Ejecución {} iniciada con el agente {} para {}",
        executionId, agent.id(), request.issueKey());
    return run(agent, context, MAX_STEPS);
  }

  private AgentExecutionResult run(final Agent agent, final AgentContext context, final int stepsLeft) {
    if (stepsLeft <= 0) {
      log.warn("Ejecución {} del agente {} alcanzó el límite de pasos", context.executionId(), agent.id());
      return new AgentExecutionResult(context.executionId(), AgentStatus.FAILED,
          "Se alcanzó el límite de pasos sin completar la tarea");
    }

    final AgentResult result = agent.execute(context);

    return switch (result.status()) {
      case COMPLETED, FAILED, NEEDS_INPUT ->
          new AgentExecutionResult(context.executionId(), result.status(), result.message());
      case WAITING_FOR_TOOL -> {
        applyToolActions(agent, context, result.actions());
        yield run(agent, context, stepsLeft - 1);
      }
      case WAITING_FOR_AGENT -> run(resolveDelegate(result.actions()), context, stepsLeft - 1);
      case CONTINUE -> run(agent, context, stepsLeft - 1);
    };
  }

  private void applyToolActions(final Agent agent, final AgentContext context,
      final List<AgentAction> actions) {
    for (final AgentAction action : actions) {
      if (action.type() != ActionType.TOOL) {
        continue;
      }
      final ToolResult toolResult = executeTool(agent, context, action);
      context.addMessage(new AgentMessage(MessageRole.TOOL, action.target(), describe(toolResult)));
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
    final ToolArguments arguments = new ToolArguments(action.arguments());
    try {
      return tool.execute(toolContext, arguments);
    } catch (Exception e) {
      log.warn("Fallo ejecutando la tool {}", tool.name(), e);
      return ToolResult.failure("Error ejecutando " + tool.name() + ": " + e.getMessage());
    }
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

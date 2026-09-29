package es.colorbaby.microservices.dev.relay.ai.agent;

import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentMessage;
import es.colorbaby.microservices.dev.relay.ai.knowledge.record.KnowledgeContext;
import es.colorbaby.microservices.dev.relay.ai.skill.Skill;
import es.colorbaby.microservices.dev.relay.ai.tool.Tool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Implementación mutable de {@link AgentContext} para una ejecución concreta: acumula mensajes y
 * estado a medida que el {@link es.colorbaby.microservices.dev.relay.ai.orchestration.AgentRuntime}
 * hace avanzar al agente.
 */
public class DefaultAgentContext implements AgentContext {

  private final String executionId;
  private final String issueKey;
  private final String taskTitle;
  private final String taskDescription;
  private final List<AgentMessage> messages = new CopyOnWriteArrayList<>();
  private final List<Skill> skills;
  private final KnowledgeContext knowledge;
  private final List<Tool> availableTools;
  private final Map<String, Object> state = new ConcurrentHashMap<>();

  public DefaultAgentContext(
      final String executionId,
      final String issueKey,
      final String taskTitle,
      final String taskDescription,
      final List<Skill> skills,
      final KnowledgeContext knowledge,
      final List<Tool> availableTools) {
    this.executionId = executionId;
    this.issueKey = issueKey;
    this.taskTitle = taskTitle;
    this.taskDescription = taskDescription;
    this.skills = List.copyOf(skills);
    this.knowledge = knowledge;
    this.availableTools = List.copyOf(availableTools);
  }

  @Override
  public String executionId() {
    return executionId;
  }

  @Override
  public String issueKey() {
    return issueKey;
  }

  @Override
  public String taskTitle() {
    return taskTitle;
  }

  @Override
  public String taskDescription() {
    return taskDescription;
  }

  @Override
  public List<AgentMessage> messages() {
    return List.copyOf(messages);
  }

  @Override
  public List<Skill> skills() {
    return skills;
  }

  @Override
  public KnowledgeContext knowledge() {
    return knowledge;
  }

  @Override
  public List<Tool> availableTools() {
    return availableTools;
  }

  @Override
  public Map<String, Object> state() {
    return Map.copyOf(state);
  }

  @Override
  public void addMessage(final AgentMessage message) {
    messages.add(message);
  }

  @Override
  public void putState(final String key, final Object value) {
    state.put(key, value);
  }
}

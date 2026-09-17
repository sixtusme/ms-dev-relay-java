package es.colorbaby.microservices.dev.relay.ai.agent;

import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentMessage;
import es.colorbaby.microservices.dev.relay.ai.knowledge.record.KnowledgeContext;
import es.colorbaby.microservices.dev.relay.ai.skill.Skill;
import es.colorbaby.microservices.dev.relay.ai.tool.Tool;

import java.util.List;
import java.util.Map;

public interface AgentContext {
    String executionId();
    String issueKey();
    String taskTitle();
    String taskDescription();

    /**
     * Información acumulada durante la ejecución.
     *
     * @return list amount of messages.
     */
    List<AgentMessage> messages();

    /**
     * Skills seleccionados para esta ejecucción.
     *
     * @return list amount of skills.
     */
    List<Skill> skills();

    /**
     * Conocimientos recuperados.
     *
     * @return devuelve conocimientos.
     */
    KnowledgeContext knowledge();

    /**
     * Tools que el agente puede utilizar.
     *
     * @return tools disponibles.
     */
    List<Tool> availableTools();

    /**
     * Estado arbitrario persistente de la ejecución.
     *
     * @return devuelve states.
     */
    Map<String, Object> state();

    /**
     * Añade información al contexto.
     */
    void addMessage(AgentMessage message);

    /**
     * Guarda un valor de estado.
     */
    void putState(String key, Object value);
}

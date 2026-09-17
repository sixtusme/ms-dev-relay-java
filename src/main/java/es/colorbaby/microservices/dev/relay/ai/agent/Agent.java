package es.colorbaby.microservices.dev.relay.ai.agent;

import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentResult;

public interface Agent {

    /**
     * Identificador estable del agente.
     * Ej: planner, coder, reviewer, diagnostician.
     */
    String id();

    /**
     * Descripción utilizada por el orquestador/router.
     */
    String description();

    /**
     * Ejecuta el agente con el contexto actual.
     */
    AgentResult execute(AgentContext context);
}

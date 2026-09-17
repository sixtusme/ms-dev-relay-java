package es.colorbaby.microservices.dev.relay.ai.agent.record;

import es.colorbaby.microservices.dev.relay.ai.agent.state.AgentStatus;

import java.util.List;

public record AgentResult (
        AgentStatus status,
        String message,
        List<AgentAction> actions
){ }

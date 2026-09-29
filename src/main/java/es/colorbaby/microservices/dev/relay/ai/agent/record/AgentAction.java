package es.colorbaby.microservices.dev.relay.ai.agent.record;

import es.colorbaby.microservices.dev.relay.ai.agent.state.ActionType;

import java.util.Map;

public record AgentAction(
        ActionType type,
        String target,
        Map<String, Object> arguments
) {
}

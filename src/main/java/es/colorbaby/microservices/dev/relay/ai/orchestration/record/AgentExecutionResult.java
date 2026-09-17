package es.colorbaby.microservices.dev.relay.ai.orchestration.record;

import es.colorbaby.microservices.dev.relay.ai.agent.state.AgentStatus;

public record AgentExecutionResult(
        String executionId,
        AgentStatus status,
        String summary
) {
}

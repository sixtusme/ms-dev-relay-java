package es.colorbaby.microservices.dev.relay.ai.tool.record;

import es.colorbaby.microservices.dev.relay.ai.agent.AgentContext;

public record ToolContext(
        String executionId,
        String issueKey,
        String agentId,
        AgentContext agentContext
) {
}

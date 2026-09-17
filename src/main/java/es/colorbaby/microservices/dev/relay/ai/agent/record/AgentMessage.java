package es.colorbaby.microservices.dev.relay.ai.agent.record;

import es.colorbaby.microservices.dev.relay.ai.agent.state.MessageRole;

public record AgentMessage(
        MessageRole role,
        String source,
        String content
) {
}

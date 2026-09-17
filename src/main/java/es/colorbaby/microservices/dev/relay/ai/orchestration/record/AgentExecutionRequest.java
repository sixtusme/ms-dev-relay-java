package es.colorbaby.microservices.dev.relay.ai.orchestration.record;

public record AgentExecutionRequest(
        String issueKey,
        String title,
        String description,
        String initialAgent
) {
}

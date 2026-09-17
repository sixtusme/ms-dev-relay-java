package es.colorbaby.microservices.dev.relay.ai.orchestration;

public interface AgentRuntime {
    AgentExecutionResult execute(
            AgentExecutionRequest request
    );
}

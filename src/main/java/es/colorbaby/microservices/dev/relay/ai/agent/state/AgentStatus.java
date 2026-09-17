package es.colorbaby.microservices.dev.relay.ai.agent.state;

public enum AgentStatus {
    COMPLETED,
    CONTINUE,
    WAITING_FOR_TOOL,
    WAITING_FOR_AGENT,
    NEEDS_INPUT,
    FAILED
}

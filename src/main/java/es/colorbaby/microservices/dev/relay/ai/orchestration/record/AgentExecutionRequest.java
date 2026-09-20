package es.colorbaby.microservices.dev.relay.ai.orchestration.record;

import java.util.Map;

/**
 * @param parameters datos extra que necesite el agente inicial (ej. {@code repo}/{@code branch}
 *                    para el coder), sembrados en el estado del {@code AgentContext} antes de la
 *                    primera llamada. Nunca null tras el constructor.
 */
public record AgentExecutionRequest(
        String issueKey,
        String title,
        String description,
        String initialAgent,
        Map<String, Object> parameters
) {

    public AgentExecutionRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    /** Petición sin parámetros extra. */
    public static AgentExecutionRequest of(
            final String issueKey, final String title, final String description,
            final String initialAgent) {
        return new AgentExecutionRequest(issueKey, title, description, initialAgent, Map.of());
    }
}

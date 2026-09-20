package es.colorbaby.microservices.dev.relay.ai.orchestration.record;

import es.colorbaby.microservices.dev.relay.ai.agent.state.AgentStatus;
import java.util.Map;

/**
 * @param data salida estructurada opcional del agente al terminar (ej. {@code changedFiles} del
 *             coder), para que quien llamó al runtime pueda encadenar otro agente sin tener que
 *             volver a derivar esa información. Vacío si no aporta nada más allá de {@code summary}.
 */
public record AgentExecutionResult(
        String executionId,
        AgentStatus status,
        String summary,
        Map<String, Object> data
) {

    public AgentExecutionResult(final String executionId, final AgentStatus status, final String summary) {
        this(executionId, status, summary, Map.of());
    }
}

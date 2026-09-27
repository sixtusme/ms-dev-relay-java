package es.colorbaby.microservices.dev.relay.ai.orchestration;

import es.colorbaby.microservices.dev.relay.ai.orchestration.record.AgentExecutionRequest;
import es.colorbaby.microservices.dev.relay.ai.orchestration.record.AgentExecutionResult;
import java.util.Optional;

public interface AgentRuntime {
    AgentExecutionResult execute(
            AgentExecutionRequest request
    );

    /**
     * Continúa una ejecución que se quedó en {@code NEEDS_INPUT} para {@code issueKey}/{@code repo},
     * con la respuesta que ha dado una persona: reconstruye el {@code AgentContext} guardado y
     * sigue el mismo bucle LLM→tool→LLM desde donde se quedó, nunca reinicia al agente desde cero.
     *
     * @return vacío si no había ninguna ejecución de {@code agentId} pendiente para esa tarea/repo
     */
    Optional<AgentExecutionResult> resume(
            String issueKey, String repo, String agentId, String answer
    );
}

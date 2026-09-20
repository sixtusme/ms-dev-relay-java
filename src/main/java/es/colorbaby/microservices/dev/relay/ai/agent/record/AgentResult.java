package es.colorbaby.microservices.dev.relay.ai.agent.record;

import es.colorbaby.microservices.dev.relay.ai.agent.state.AgentStatus;

import java.util.List;
import java.util.Map;

/**
 * @param data salida estructurada opcional del paso (ej. las rutas que el coder acaba de
 *             commitear, para que quien orquesta pueda pasárselas a otro agente sin tener que
 *             volver a leer el {@code AgentContext}, que es interno al runtime). Vacío si el paso
 *             no tiene nada que aportar más allá de {@code message}.
 */
public record AgentResult (
        AgentStatus status,
        String message,
        List<AgentAction> actions,
        Map<String, Object> data
){

    public AgentResult(final AgentStatus status, final String message, final List<AgentAction> actions) {
        this(status, message, actions, Map.of());
    }
}

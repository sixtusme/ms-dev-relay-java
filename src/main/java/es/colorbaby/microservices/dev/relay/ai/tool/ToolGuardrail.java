package es.colorbaby.microservices.dev.relay.ai.tool;

import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;

/**
 * El paso "Apply Guardrails" del pipeline de ejecución de tools: valida o ajusta los argumentos de
 * una tool ANTES de ejecutarla. Se registra como bean y el {@link
 * es.colorbaby.microservices.dev.relay.ai.orchestration.AgentRuntime} lo aplica de forma genérica a
 * cualquier tool a la que declare aplicarse — así un guardarraíl protege a todos los agentes que
 * usen esa tool, no solo al que lo tuviera cableado a mano.
 */
public interface ToolGuardrail {

  /** Si este guardarraíl debe aplicarse a esta tool. */
  boolean appliesTo(Tool tool);

  /** Deja pasar los argumentos (posiblemente ajustados) o deniega la ejecución con un motivo. */
  Outcome check(ToolContext context, ToolArguments arguments);

  /** Resultado de un guardarraíl: o los argumentos con los que seguir, o una denegación. */
  record Outcome(ToolArguments arguments, ToolResult denial) {

    public static Outcome allow(final ToolArguments arguments) {
      return new Outcome(arguments, null);
    }

    public static Outcome deny(final String reason) {
      return new Outcome(null, ToolResult.denied(reason));
    }

    public boolean denied() {
      return denial != null;
    }
  }
}

package es.colorbaby.microservices.dev.relay.control.lifecycle;

/**
 * Qué sistema o quién produjo una evidencia (mejoras-senior §6.2 y §18: la misma lista sirve para
 * "de dónde viene" y para "quién decide"). Distinto de {@link Evidence} en sí: aquí solo se
 * clasifica el origen, no el detalle. Opcional: no toda evidencia tiene todavía un origen
 * clasificado por el código que la produce.
 */
public enum EvidenceSource {
  /** El propio dev-relay, sin intervención de un agente ni de una persona (una decisión de config, un cómputo interno). */
  SYSTEM,
  /** Un agente de IA (planner, coder, reviewer, selector…) vía {@code AgentRuntime}. */
  AGENT,
  JENKINS,
  GITHUB,
  JIRA,
  /** Una persona: quien aprueba, promociona, pide una corrección o responde al panel. */
  HUMAN
}

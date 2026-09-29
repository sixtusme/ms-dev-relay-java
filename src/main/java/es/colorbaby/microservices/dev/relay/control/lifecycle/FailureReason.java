package es.colorbaby.microservices.dev.relay.control.lifecycle;

/**
 * Causa reconocida de un fallo, para no depender solo del texto libre de {@link Evidence#detail()}
 * a la hora de contar, agrupar o enrutar una remediación (mejoras-senior §7). Opcional: no todo
 * fallo tiene todavía una causa reconocida por el código que produce la evidencia.
 */
public enum FailureReason {
  BUILD_FAILED,
  TEST_FAILED,
  NO_CHANGES,
  REPO_NOT_FOUND,
  AGENT_ERROR,
  HUMAN_REJECTED,
  ACCEPTANCE_CRITERIA_FAILED,
  CORRECTION_BUDGET_EXCEEDED,
  EXTERNAL_SERVICE_UNAVAILABLE
}

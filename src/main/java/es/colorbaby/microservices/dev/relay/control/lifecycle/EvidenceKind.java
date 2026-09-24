package es.colorbaby.microservices.dev.relay.control.lifecycle;

/** Qué tipo de prueba respalda un resultado. Los artefactos (PR, informe, build) llevan URL. */
public enum EvidenceKind {
  PR,
  COMMENT,
  BUILD,
  REPORT,
  PLAN,
  REVIEW,
  DECISION,
  REASON
}

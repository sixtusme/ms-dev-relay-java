package es.colorbaby.microservices.dev.relay.command;

/**
 * Lista CERRADA de lo que sixai acepta que le pidan por comentario. El LLM solo puede devolver uno
 * de estos valores: cualquier otra cosa se degrada a {@link #UNKNOWN} y sixai pide aclaración en vez
 * de actuar. Es la regla de "herramientas tipadas, nunca acción improvisada" aplicada al lenguaje.
 */
public enum CommandIntent {

  /** Pasar a producción (merge develop→main + build/deploy). La acción de mayor riesgo. */
  PROMOTE_TO_PROD,

  /** Corregir o cambiar algo de lo entregado; el texto describe qué. */
  REVISE,

  /** Repetir el despliegue sin cambios de código. */
  REDEPLOY,

  /** Consulta de estado: responde, no actúa. */
  STATUS,

  /** Abandonar el trabajo en curso. */
  CANCEL,

  /** No se entendió: sixai pide aclaración y NO actúa. */
  UNKNOWN
}

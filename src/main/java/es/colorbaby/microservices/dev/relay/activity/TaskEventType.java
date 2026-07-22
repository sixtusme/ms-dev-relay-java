package es.colorbaby.microservices.dev.relay.activity;

/**
 * Hitos de la vida de una tarea. Se guardan en {@code task_event} como línea de tiempo append-only:
 * nunca se actualizan, solo se añaden. Es lo que permite responder "cuéntame qué pasó con esta
 * tarea" sin reconstruirlo de logs.
 */
public enum TaskEventType {

  /** sixai ha cogido la tarea (comentario disparador detectado). */
  DETECTED,

  /** Ha respondido en la tarea y la ha puesto en curso. */
  IN_PROGRESS,

  /** El coder ha generado código. */
  CODE_GENERATED,

  /** Se ha abierto una PR hacia develop. */
  PR_OPENED,

  /** Alguien ha aprobado el merge desde el front. */
  APPROVED,

  /** La PR se ha mergeado a develop. */
  MERGED,

  /** Se ha lanzado un build. */
  BUILD_STARTED,

  /** Un build terminó bien / mal. */
  BUILD_OK,
  BUILD_FAILED,

  /** Desplegado en un entorno. */
  DEPLOYED_PRE,
  DEPLOYED_PROD,

  /** La tarea ha pasado a TEST y se ha reasignado al informador. */
  MOVED_TO_TEST,

  /** Ha llegado un comando /sixai. */
  COMMAND_RECEIVED,

  /** Se ha promocionado a producción (merge develop → main). */
  PROMOTED,

  /** Algo falló y se abortó. */
  FAILED
}

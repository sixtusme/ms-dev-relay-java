package es.colorbaby.microservices.dev.relay.deploy;

/**
 * A qué entorno va un despliegue. Decide qué hace sixai al terminar: en {@code PRE} reasigna al
 * informador y pasa la tarea a TEST; en {@code PROD} solo avisa (la tarea se queda en TEST para que
 * el cliente lo verifique en producción).
 */
public enum DeploymentPhase {
  PRE, PROD
}

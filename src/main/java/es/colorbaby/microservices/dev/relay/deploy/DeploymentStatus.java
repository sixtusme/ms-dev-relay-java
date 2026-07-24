package es.colorbaby.microservices.dev.relay.deploy;

/** Desenlace de un despliegue o de un lote: en curso, terminado bien, o fallido. */
public enum DeploymentStatus {
  RUNNING, SUCCEEDED, FAILED
}

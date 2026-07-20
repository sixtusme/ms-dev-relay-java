package es.colorbaby.microservices.dev.relay.coder;

/**
 * Un cambio de fichero propuesto por el coder: la ruta, si se crea o actualiza, y su contenido
 * COMPLETO (no un diff). Al aplicarlo se hace upsert real según el fichero exista o no en la rama.
 */
public record FileChange(String path, ChangeType type, String content) {

  /** Tipo de cambio declarado por el modelo (orientativo; el upsert decide de verdad). */
  public enum ChangeType {
    CREATE, UPDATE
  }
}

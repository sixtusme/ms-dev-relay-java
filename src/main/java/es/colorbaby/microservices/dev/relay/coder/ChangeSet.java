package es.colorbaby.microservices.dev.relay.coder;

import java.util.List;

/**
 * El resultado del coder: un resumen de lo hecho y la lista de cambios de fichero. Vacío significa
 * que el modelo no propuso nada aplicable (se cae al placeholder).
 */
public record ChangeSet(String summary, List<FileChange> changes) {

  public ChangeSet {
    changes = changes == null ? List.of() : List.copyOf(changes);
  }

  /** True si no hay ningún cambio que aplicar. */
  public boolean isEmpty() {
    return changes.isEmpty();
  }
}

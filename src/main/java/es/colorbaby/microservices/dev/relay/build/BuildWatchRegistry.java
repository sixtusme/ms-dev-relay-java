package es.colorbaby.microservices.dev.relay.build;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.stereotype.Component;

/**
 * Registro en memoria de los builds pendientes de observar. Seguro para varios hilos (el observador
 * programado y los hilos de detección que encolan). En memoria a propósito para la Fase 1: la
 * persistencia del "run" llega con la máquina de estados (ver /ai/bucle-entrega-y-auto-reparacion).
 */
@Component
public class BuildWatchRegistry {

  private final Collection<BuildWatch> watches = new ConcurrentLinkedQueue<>();

  /** Añade un build a vigilar. */
  public void add(final BuildWatch watch) {
    watches.add(watch);
  }

  /** Deja de vigilar un build (terminado o agotado). */
  public void remove(final BuildWatch watch) {
    watches.remove(watch);
  }

  /** Copia inmutable de los watches actuales, para iterar sin sorpresas de concurrencia. */
  public List<BuildWatch> snapshot() {
    return List.copyOf(watches);
  }

  /** True si no hay nada que vigilar (para que el barrido salga cuanto antes). */
  public boolean isEmpty() {
    return watches.isEmpty();
  }
}

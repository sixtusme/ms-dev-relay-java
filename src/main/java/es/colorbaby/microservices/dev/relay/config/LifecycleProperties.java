package es.colorbaby.microservices.dev.relay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ciclo de vida explícito de las tareas ({@code maestro.lifecycle}).
 *
 * <p>Arranca en modo registro a propósito: las reglas se estrenan observando el flujo real, y cada
 * cosa que el modo estricto habría frenado queda como {@code LIFECYCLE_VIOLATION} sin cambiar el
 * comportamiento. Se pasa a {@code true} cuando esos avisos lleven tiempo sin aparecer.
 */
@Data
@ConfigurationProperties(prefix = "maestro.lifecycle")
public class LifecycleProperties {

  /** Con {@code true}, una regla incumplida bloquea; con {@code false}, solo avisa. */
  private boolean enforce = false;
}

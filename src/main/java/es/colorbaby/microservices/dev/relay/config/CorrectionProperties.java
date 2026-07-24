package es.colorbaby.microservices.dev.relay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Ciclo de corrección ({@code maestro.correction}): qué hace sixai cuando el cliente dice que algo
 * está mal, o cuando un build/despliegue falla.
 *
 * <p>El tope de ciclos es lo que impide que esto se convierta en un bucle: si tras varios intentos
 * la cosa no mejora, sixai <b>para y avisa a una persona</b> en vez de seguir quemando CI. Cada
 * ciclo pasa además por la aprobación humana del front, que es el freno natural.
 */
@Data
@ConfigurationProperties(prefix = "maestro.correction")
public class CorrectionProperties {

  /** Interruptor general. Con {@code false}, sixai anota la petición pero no corrige. */
  private boolean enabled = false;

  /** Simulación: loguea qué corregiría, sin abrir nada. */
  private boolean dryRun = true;

  /** Ciclos de corrección permitidos por tarea antes de rendirse y escalar. */
  private int maxCycles = 3;

  /** Si un build o despliegue fallido dispara la corrección por su cuenta. */
  private boolean autoFixOnFailure = true;
}

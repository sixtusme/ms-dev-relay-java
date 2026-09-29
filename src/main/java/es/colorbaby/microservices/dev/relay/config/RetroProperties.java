package es.colorbaby.microservices.dev.relay.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Retro de tareas completadas ({@code maestro.retro}): al llegar a {@code DONE}, si hubo algo que
 * corregir por el camino, sixai escribe una lección corta en el {@code AGENTS.md} de cada repo que
 * tocó — para que la próxima vez (un agente o una persona) lo tenga en cuenta. Requiere además
 * {@code maestro.llm.enabled=true}.
 */
@ConfigurationProperties(prefix = "maestro.retro")
@Validated
@Getter
@Setter
public class RetroProperties {

  /** Interruptor general. */
  private boolean enabled = false;

  /** Con {@code true} se registra qué lección escribiría, sin comitear nada. */
  private boolean dryRun = true;

  /** Fichero del repo donde se anota la lección (se crea si no existe). */
  private String targetFile = "AGENTS.md";
}

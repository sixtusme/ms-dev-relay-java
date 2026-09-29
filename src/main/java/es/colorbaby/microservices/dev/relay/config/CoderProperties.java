package es.colorbaby.microservices.dev.relay.config;

import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Qué hace el coder de sixai ({@code maestro.coder}): generar el código real de la tarea dentro de
 * la PR (en vez de un placeholder), usando el LLM. Requiere además {@code maestro.llm.enabled=true}.
 * Los límites acotan el contexto que se manda al modelo y el tamaño del cambio, para no dispararse.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "maestro.coder")
public class CoderProperties {

  /** Interruptor general. Con {@code false}, la PR lleva solo el placeholder (comportamiento previo). */
  private boolean enabled = false;

  /** Simulación: genera y loguea los cambios propuestos, pero NO los commitea. */
  private boolean dryRun = true;

  /** Máximo de ficheros existentes que el coder puede pedir leer como contexto. */
  @Positive
  private int maxContextFiles = 8;

  /** Máximo de caracteres por fichero de contexto (se trunca la cola si excede). */
  @Positive
  private int maxFileChars = 8000;

  /** Máximo de rutas del árbol del repo que se le pasan al modelo (se acota para no reventar). */
  @Positive
  private int treeMaxEntries = 300;

  /** Máximo de ficheros que el coder puede crear/modificar en un cambio. */
  @Positive
  private int maxChanges = 15;

  /** Prefijo del mensaje de commit de los cambios del coder. */
  private String commitMessagePrefix = "feat(sixai): ";
}

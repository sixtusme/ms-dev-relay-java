package es.colorbaby.microservices.dev.relay.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Comandos dirigidos a sixai ({@code maestro.command}): {@code /sixai <texto>} o
 * {@code @sixai <texto>} en una tarea ya en curso o en TEST. El prefijo es lo que distingue
 * "hablar DE sixai" de "hablar CON sixai" en una tarea llena de comentarios humanos.
 *
 * <p>El texto que sigue al prefijo lo interpreta el LLM y se acota a una lista cerrada de
 * intenciones. Ver /ai/ciclo-de-vida-y-comandos.
 */
@Data
@ConfigurationProperties(prefix = "maestro.command")
public class CommandProperties {

  /** Interruptor general. Con {@code false}, sixai ignora los comandos. */
  private boolean enabled = false;

  /** Simulación: interpreta y loguea qué haría, sin comentar ni actuar en Jira. */
  private boolean dryRun = true;

  /** Prefijos que marcan un comentario dirigido a sixai. */
  private List<String> prefixes = List.of("/", "@");

  /**
   * Estados de Jira en los que se aceptan comandos (nombres EXACTOS). El estado da el contexto:
   * en {@code TEST} un comando es promoción/consulta; en {@code En curso}, corrección.
   */
  private List<String> statuses = List.of("En curso", "TEST");

  /**
   * AccountIds/emails con permiso EXTRA para pasar a PROD, además del informador y el asignado
   * (que siempre pueden). Pasar a producción es la acción de mayor riesgo: lista corta y auditada.
   */
  private List<String> promoteAuthorized = List.of();
}

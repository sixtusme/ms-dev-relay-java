package es.colorbaby.microservices.dev.relay.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de qué hace Sixai al detectar una tarea elegible ({@code maestro.responder}).
 */
@ConfigurationProperties(prefix = "maestro.responder")
@Getter
@Setter
public class ResponderProperties {

  /**
   * Nombre del estado al que se mueve la tarea. Debe coincidir exacto con el flujo de trabajo de
   * Jira. Si no coincide, no se mueve (se loguea) pero el comentario se publica igual.
   */
  private String transitionTo = "En curso";

  /** Comentario que se publica cuando la IA está apagada ({@code maestro.llm.enabled=false}). */
  private String ackComment =
      "Sixai ha recibido esta tarea y la ha puesto en curso. La respuesta automática está "
          + "desactivada por ahora.";

  /**
   * Modo simulación. Con {@code true}, Sixai corre toda la tubería (detección, filtros e incluso el
   * LLM) pero NO escribe en Jira: comentarios, transiciones y etiquetas se omiten y se loguean con
   * el prefijo {@code [DRY-RUN]}. Ideal para probar modelos y prompts sobre tareas reales sin tocar
   * nada. Lo aplica {@code DryRunJiraClient}, no este responder.
   */
  private boolean dryRun = false;

  /** Si es true, el comentario menciona (@) al autor del comentario que disparó el trigger. */
  private boolean mentionAuthor = true;

  /** Si es true, el comentario cita el texto del comentario disparador antes de responder. */
  private boolean quoteComment = true;

  /** Si es true, ante un fallo procesando la tarea se publica un comentario avisando del error. */
  private boolean commentOnError = true;

  /** Etiqueta que se pone a la tarea cuando el procesamiento falla. Vacío = no etiquetar. */
  private String errorLabel = "sixai-error";
}

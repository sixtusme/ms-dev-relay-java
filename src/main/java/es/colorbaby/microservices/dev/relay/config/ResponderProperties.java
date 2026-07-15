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
}

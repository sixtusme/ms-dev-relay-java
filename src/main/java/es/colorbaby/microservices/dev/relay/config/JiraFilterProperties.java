package es.colorbaby.microservices.dev.relay.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Filtro de elegibilidad aplicado por igual a webhook y polling: una tarea
 * solo se procesa si está asignada a alguien permitido y tiene un
 * comentario con la palabra clave configurada.
 */
@Data
@ConfigurationProperties(prefix = "maestro.jira.filter")
public class JiraFilterProperties {

  /**
   * Emails (Server/DC) o accountIds (Cloud) de asignados permitidos.
   */
  private List<String> allowedAssignees = List.of();

  /**
   * Palabra clave que debe contener un comentario para disparar el procesamiento.
   */
  private String triggerKeyword = "sixai";

  /**
   * Si es true, el comentario con la keyword debe haberlo escrito el propio
   * asignado o alguien en trustedTriggerAuthors.
   */
  private boolean commentAuthorMustBeAssignee = true;

  /**
   * AccountIds/emails adicionales autorizados a disparar el trigger aunque
   * no sean el asignado.
   */
  private List<String> trustedTriggerAuthors = List.of();
}

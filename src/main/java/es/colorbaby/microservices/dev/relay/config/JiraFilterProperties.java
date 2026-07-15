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

  /**
   * Estados de Jira que Sixai revisa. Solo las tareas en uno de estos estados se procesan; las
   * demás (finalizadas, en curso, descartadas…) se ignoran. Vacío = sin filtro de estado.
   *
   * <p>Son NOMBRES exactos del flujo de Jira (p. ej. "TAREAS EN COLA"), no etiquetas de columna.
   */
  private List<String> statuses = List.of();

  /**
   * Etiqueta con la que Sixai marca una tarea ya procesada. Es la clave de idempotencia: las tareas
   * con esta etiqueta se excluyen en el JQL del polling y se descartan en el filtro (también por
   * webhook), así no se reprocesan aunque el servicio se reinicie. Vacío = sin marca de idempotencia.
   */
  private String processedLabel = "sixai-procesada";
}

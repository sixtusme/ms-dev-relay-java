package es.colorbaby.microservices.dev.relay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * El reviewer de sixai ({@code maestro.reviewer}): revisa el {@code ChangeSet} que el coder acaba
 * de commitear (léelo, nunca lo toca) y comenta un veredicto en la tarea de Jira, junto al enlace
 * de la PR. Requiere además {@code maestro.llm.enabled=true}.
 *
 * <p><b>Nunca bloquea nada</b>: es una opinión más para quien aprueba, igual que el veredicto de
 * {@code VerificationService}. La autorización de mergear sigue dependiendo solo de la identidad
 * de quien aprueba en el front, nunca de lo que diga el reviewer.
 */
@Data
@ConfigurationProperties(prefix = "maestro.reviewer")
public class ReviewerProperties {

  private boolean enabled = false;

  /** Máximo de ficheros que se leen para la revisión (limita el tamaño del prompt). */
  private int maxFiles = 10;

  /** Máximo de caracteres por fichero revisado. */
  private int maxFileChars = 8000;
}

package es.colorbaby.microservices.dev.relay.responder;

import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.config.ResponderProperties;
import es.colorbaby.microservices.dev.relay.event.IssueEligibleEvent;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.jira.util.JiraTextExtractor;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFields;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Responde una tarea de Jira elegible: publica un comentario y la pone en curso.
 *
 * <p>Con la IA apagada ({@code maestro.llm.enabled=false}) el comentario es el texto fijo de
 * {@code maestro.responder.ack-comment} y NO se llama a ningún modelo. Con la IA encendida, el
 * comentario es la respuesta del LLM a la descripción de la tarea.
 *
 * <p>Se engancha a {@link IssueEligibleEvent}, que publica la capa de detección. Esa capa no
 * conoce a este componente: solo comparten el evento. El evento se publica dentro del hilo
 * asíncrono de la detección, así que este listener corre en ese hilo; cualquier error se contiene
 * aquí (se loguea, no se propaga) para no romper el ciclo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IssueResponder {

  private final JiraClient jiraClient;
  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final ResponderProperties responderProperties;

  @EventListener
  public void onIssueEligible(final IssueEligibleEvent event) {
    String issueKey = event.issueKey();
    try {
      String comment = buildComment(issueKey);
      if (comment != null && !comment.isBlank()) {
        jiraClient.addComment(issueKey, comment);
        log.info("Comentario publicado en {}", issueKey);
      }
      moveToInProgress(issueKey);
    } catch (RuntimeException e) {
      // No se relanza: un fallo respondiendo no debe tumbar el ciclo de detección.
      log.error("No se pudo responder la issue {}", issueKey, e);
    }
  }

  /** Comentario a publicar: respuesta del LLM si está activo, o el texto fijo si no. */
  private String buildComment(final String issueKey) {
    if (!llmProperties.isEnabled()) {
      return responderProperties.getAckComment();
    }

    JiraIssueDto issue = jiraClient.getIssue(issueKey);
    JiraIssueDtoFields fields = issue == null ? null : issue.getFields();
    if (fields == null) {
      log.warn("La issue {} no tiene campos; se usa el comentario fijo", issueKey);
      return responderProperties.getAckComment();
    }

    String summary = fields.getSummary() == null ? "" : fields.getSummary();
    // La descripción llega como ADF (objeto) o texto plano; el extractor cubre ambos casos.
    String description = JiraTextExtractor.extractPlainText(fields.getDescription());

    String userPrompt = "Título de la tarea: " + summary + "\n\n"
        + "Descripción:\n"
        + (description == null || description.isBlank() ? "(sin descripción)" : description);

    return llmClient.complete(llmProperties.getSystemPrompt(), userPrompt);
  }

  /**
   * Mueve la tarea al estado configurado. Best-effort: si el estado no existe en el flujo o la
   * transición no es válida desde el estado actual, se loguea y no se interrumpe el resto.
   */
  private void moveToInProgress(final String issueKey) {
    String target = responderProperties.getTransitionTo();
    if (target == null || target.isBlank()) {
      return;
    }
    try {
      jiraClient.transitionIssueByStatusName(issueKey, target);
      log.info("Issue {} movida a '{}'", issueKey, target);
    } catch (RuntimeException e) {
      log.warn("No se pudo mover la issue {} a '{}': {}", issueKey, target, e.getMessage());
    }
  }
}

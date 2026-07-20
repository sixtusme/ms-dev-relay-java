package es.colorbaby.microservices.dev.relay.responder;

import es.colorbaby.microservices.dev.relay.config.JiraFilterProperties;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.config.ResponderProperties;
import es.colorbaby.microservices.dev.relay.event.IssueEligibleEvent;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.jira.util.JiraAdf;
import es.colorbaby.microservices.dev.relay.jira.util.JiraTextExtractor;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFields;
import es.colorbaby.microservices.dev.relay.pullrequest.PullRequestService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Responde una tarea de Jira elegible: publica un comentario, la pone en curso, la marca como
 * procesada (etiqueta de idempotencia) y arranca el trabajo en GitHub abriendo las PRs necesarias
 * ({@link PullRequestService}).
 *
 * <p>Con la IA apagada ({@code maestro.llm.enabled=false}) el comentario es el texto fijo de
 * {@code maestro.responder.ack-comment} y NO se llama a ningún modelo. Con la IA encendida, el
 * comentario es la respuesta del LLM a la descripción de la tarea. Si hay mención/cita configuradas,
 * el comentario se publica como ADF mencionando al autor del comentario disparador y citándolo.
 *
 * <p>Se engancha a {@link IssueEligibleEvent}, que publica la capa de detección. Esa capa no conoce
 * a este componente: solo comparten el evento. El evento se publica dentro del hilo asíncrono de la
 * detección, así que este listener corre en ese hilo; cualquier error se contiene aquí (se loguea y,
 * si está configurado, se avisa en la propia tarea) para no romper el ciclo.
 *
 * <p>En modo simulación ({@code maestro.responder.dry-run=true}) las escrituras no llegan a Jira:
 * las intercepta {@code DryRunJiraClient} y las loguea. Este responder no necesita saberlo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IssueResponder {

  /** Tope de caracteres de la cita para no arrastrar comentarios enormes. */
  private static final int QUOTE_MAX_CHARS = 500;

  private final JiraClient jiraClient;
  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final ResponderProperties responderProperties;
  private final JiraFilterProperties filterProperties;
  private final PullRequestService pullRequestService;

  @EventListener
  public void onIssueEligible(final IssueEligibleEvent event) {
    String issueKey = event.issueKey();
    try {
      String replyText = buildReplyText(issueKey);
      if (replyText != null && !replyText.isBlank()) {
        log.info("Respuesta de Sixai para {}:\n{}", issueKey, replyText);
        publishReply(issueKey, replyText, event);
        log.info("Comentario publicado en {}", issueKey);
      }
      moveToInProgress(issueKey);
      markProcessed(issueKey);
      pullRequestService.openForIssue(issueKey);
    } catch (RuntimeException e) {
      // No se relanza: un fallo respondiendo no debe tumbar el ciclo de detección.
      log.error("No se pudo responder la issue {}", issueKey, e);
      reportError(issueKey, e);
    }
  }

  /** Texto de la respuesta: salida del LLM si está activo, o el texto fijo si no. */
  private String buildReplyText(final String issueKey) {
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

    return llmClient.complete(LlmRequest.of(
        llmProperties.getSystemPrompt(), userPrompt, LlmRoles.RESPONDER, issueKey));
  }

  /**
   * Publica la respuesta. Con mención o cita configuradas (y datos del comentario disparador
   * disponibles) usa ADF; si no, un comentario de texto plano.
   */
  private void publishReply(String issueKey, String replyText, IssueEligibleEvent event) {
    boolean withMention = responderProperties.isMentionAuthor()
        && event.triggeringCommentAuthorAccountId() != null;
    boolean withQuote = responderProperties.isQuoteComment()
        && event.triggeringCommentText() != null && !event.triggeringCommentText().isBlank();

    if (!withMention && !withQuote) {
      jiraClient.addComment(issueKey, replyText);
      return;
    }
    jiraClient.addCommentAdf(issueKey, buildAdfReply(replyText, event, withMention, withQuote));
  }

  /** Documento ADF: cita del comentario disparador (opcional) + párrafo con @mención + respuesta. */
  private Object buildAdfReply(String replyText, IssueEligibleEvent event,
      boolean withMention, boolean withQuote) {
    List<Map<String, Object>> blocks = new ArrayList<>();

    if (withQuote) {
      String quoted = truncate(event.triggeringCommentText(), QUOTE_MAX_CHARS);
      blocks.add(JiraAdf.blockquote(List.of(
          JiraAdf.paragraph(List.of(JiraAdf.text(quoted))))));
    }

    List<Map<String, Object>> inline = new ArrayList<>();
    if (withMention) {
      inline.add(JiraAdf.mention(event.triggeringCommentAuthorAccountId(),
          event.triggeringCommentAuthorName()));
      inline.add(JiraAdf.text(" "));
    }
    inline.add(JiraAdf.text(replyText));
    blocks.add(JiraAdf.paragraph(inline));

    return JiraAdf.doc(blocks);
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

  /** Marca la tarea como procesada (idempotencia). Best-effort: un fallo aquí solo se loguea. */
  private void markProcessed(final String issueKey) {
    String label = filterProperties.getProcessedLabel();
    if (label == null || label.isBlank()) {
      return;
    }
    try {
      jiraClient.addLabel(issueKey, label);
      log.info("Issue {} marcada como procesada (etiqueta '{}')", issueKey, label);
    } catch (RuntimeException e) {
      log.warn("No se pudo marcar {} como procesada: {}", issueKey, e.getMessage());
    }
  }

  /** Feedback de error en la propia tarea: comentario y/o etiqueta, según config. Best-effort. */
  private void reportError(final String issueKey, final RuntimeException cause) {
    if (responderProperties.isCommentOnError()) {
      try {
        jiraClient.addComment(issueKey,
            "⚠️ Sixai no pudo procesar esta tarea automáticamente: " + rootMessage(cause));
      } catch (RuntimeException e) {
        log.warn("Tampoco se pudo comentar el error en {}: {}", issueKey, e.getMessage());
      }
    }
    String errorLabel = responderProperties.getErrorLabel();
    if (errorLabel != null && !errorLabel.isBlank()) {
      try {
        jiraClient.addLabel(issueKey, errorLabel);
      } catch (RuntimeException e) {
        log.warn("No se pudo etiquetar el error en {}: {}", issueKey, e.getMessage());
      }
    }
  }

  private static String truncate(String value, int max) {
    String stripped = value == null ? "" : value.strip();
    return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
  }

  private static String rootMessage(Throwable throwable) {
    Throwable root = throwable;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
  }
}

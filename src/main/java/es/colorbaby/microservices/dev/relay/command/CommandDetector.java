package es.colorbaby.microservices.dev.relay.command;

import es.colorbaby.microservices.dev.relay.config.CommandProperties;
import es.colorbaby.microservices.dev.relay.config.JiraFilterProperties;
import es.colorbaby.microservices.dev.relay.jira.util.JiraTextExtractor;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraCommentDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFields;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraTransitionDtoTo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Encuentra en los comentarios de una tarea los que van DIRIGIDOS a sixai: los que llevan la
 * palabra clave precedida de un prefijo ({@code /sixai …}, {@code @sixai …}). El prefijo es lo que
 * separa una orden de un comentario humano que simplemente menciona a sixai, algo que importa
 * mucho en una tarea viva llena de conversación.
 *
 * <p>Devuelve solo los pendientes: descarta los ya atendidos y los anteriores al arranque
 * (ver {@link ProcessedCommentsTracker}). Lógica pura salvo el tracker.
 */
@Component
@RequiredArgsConstructor
public class CommandDetector {

  private final CommandProperties properties;
  private final JiraFilterProperties filterProperties;
  private final ProcessedCommentsTracker tracker;

  /** Comandos pendientes de una issue, en orden cronológico (los más viejos primero). */
  public List<SixaiCommand> detect(final JiraIssueDto issue, final List<JiraCommentDto> comments) {
    final List<SixaiCommand> commands = new ArrayList<>();
    if (comments == null || comments.isEmpty()) {
      return commands;
    }
    final String status = statusName(issue);
    for (final JiraCommentDto comment : comments) {
      if (comment.getId() == null || tracker.isBeforeWatermark(comment.getCreated())) {
        continue;
      }
      final String text = JiraTextExtractor.extractPlainText(comment.getBody());
      final String instruction = instructionAfterMention(text);
      if (instruction == null) {
        continue;
      }
      if (!tracker.markIfNew(comment.getId(), issue.getKey())) {
        continue;
      }
      commands.add(new SixaiCommand(issue.getKey(), comment.getId(), comment.getAuthor(),
          instruction, status));
    }
    return commands;
  }

  /**
   * Si el texto contiene una mención dirigida ({@code <prefijo>sixai}), devuelve lo que va después
   * (la instrucción); si no hay mención dirigida, devuelve null. Una mención sin prefijo NO es una
   * orden: es alguien hablando de sixai.
   */
  private String instructionAfterMention(final String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    final String keyword = filterProperties.getTriggerKeyword();
    if (keyword == null || keyword.isBlank()) {
      return null;
    }
    final String lower = text.toLowerCase(Locale.ROOT);
    final String lowerKeyword = keyword.toLowerCase(Locale.ROOT);

    for (final String prefix : properties.getPrefixes()) {
      if (prefix == null || prefix.isBlank()) {
        continue;
      }
      final int at = lower.indexOf(prefix.toLowerCase(Locale.ROOT) + lowerKeyword);
      if (at >= 0) {
        final int after = at + prefix.length() + keyword.length();
        return after >= text.length() ? "" : text.substring(after).strip();
      }
    }
    return null;
  }

  private static String statusName(final JiraIssueDto issue) {
    final JiraIssueDtoFields fields = issue == null ? null : issue.getFields();
    final JiraTransitionDtoTo status = fields == null ? null : fields.getStatus();
    return status == null ? null : status.getName();
  }
}

package es.colorbaby.microservices.dev.relay.filter;

import es.colorbaby.microservices.dev.relay.config.JiraFilterProperties;
import es.colorbaby.microservices.dev.relay.jira.util.JiraTextExtractor;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraCommentDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraUserDto;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Punto único de decisión de elegibilidad, usado tanto por el webhook como
 * por el polling: una issue solo se procesa si está asignada a alguien
 * permitido y tiene un comentario con la palabra clave configurada (y,
 * opcionalmente, escrito por el propio asignado o un autor de confianza).
 * Lógica pura, sin I/O.
 */
@Component
@RequiredArgsConstructor
public class JiraIssueEligibilityFilter {

  private final JiraFilterProperties filterProperties;

  /**
   * Comprobación rápida sin I/O: si el asignado no está permitido, el
   * llamante puede evitar ir a buscar los comentarios de la issue a Jira.
   */
  public boolean isAssigneeAllowed(JiraIssueDto issue) {
    JiraUserDto assignee = issue.getFields() == null ? null : issue.getFields().getAssignee();
    return matchesAny(assignee, filterProperties.getAllowedAssignees());
  }

  public Optional<EligibilityResult> evaluate(JiraIssueDto issue, List<JiraCommentDto> comments) {
    if (!isAssigneeAllowed(issue)) {
      return Optional.empty();
    }
    JiraUserDto assignee = issue.getFields().getAssignee();

    String keyword = filterProperties.getTriggerKeyword();
    return comments.stream()
        .filter(comment -> containsKeyword(comment, keyword))
        .filter(comment -> !filterProperties.isCommentAuthorMustBeAssignee()
            || isTrustedAuthor(comment.getAuthor(), assignee))
        .findFirst()
        .map(comment -> new EligibilityResult(issue, comment));
  }

  private boolean containsKeyword(JiraCommentDto comment, String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return false;
    }
    String text = JiraTextExtractor.extractPlainText(comment.getBody());
    return text.toLowerCase().contains(keyword.toLowerCase());
  }

  private boolean isTrustedAuthor(JiraUserDto author, JiraUserDto assignee) {
    return matchesUser(author, assignee) || matchesAny(author, filterProperties.getTrustedTriggerAuthors());
  }

  private boolean matchesAny(JiraUserDto user, List<String> identifiers) {
    if (user == null || identifiers == null) {
      return false;
    }
    return identifiers.stream().anyMatch(identifier -> matchesIdentifier(user, identifier));
  }

  private boolean matchesUser(JiraUserDto a, JiraUserDto b) {
    if (a == null || b == null) {
      return false;
    }
    return equalsIgnoreCase(a.getAccountId(), b.getAccountId())
        || equalsIgnoreCase(a.getEmailAddress(), b.getEmailAddress());
  }

  private boolean matchesIdentifier(JiraUserDto user, String identifier) {
    if (identifier == null || identifier.isBlank()) {
      return false;
    }
    return equalsIgnoreCase(user.getAccountId(), identifier)
        || equalsIgnoreCase(user.getEmailAddress(), identifier);
  }

  private boolean equalsIgnoreCase(String a, String b) {
    return a != null && b != null && a.strip().equalsIgnoreCase(b.strip());
  }
}

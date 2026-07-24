package es.colorbaby.microservices.dev.relay.command;

import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.approval.PromotionService;
import es.colorbaby.microservices.dev.relay.config.CommandProperties;
import es.colorbaby.microservices.dev.relay.correction.CorrectionService;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraCommentDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFields;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraUserDto;
import es.colorbaby.microservices.dev.relay.session.SessionQueryService;
import es.colorbaby.microservices.dev.relay.session.SixaiPrDto;
import es.colorbaby.microservices.dev.relay.session.SixaiSessionDto;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Atiende los comandos dirigidos a sixai en una tarea: detecta los pendientes, interpreta su
 * intención (acotada al catálogo) y despacha. Cada comando se atiende una sola vez
 * ({@link ProcessedCommentsTracker}) y siempre se responde en la propia tarea.
 *
 * <p>Las acciones que aún no existen (promoción a PROD, ciclo de corrección) se responden con
 * honestidad en vez de fingir que se han hecho: sixai autoriza, avisa y ahí se queda.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandService {

  private final JiraClient jiraClient;
  private final CommandDetector detector;
  private final CommandIntentInterpreter interpreter;
  private final SessionQueryService sessionQueryService;
  private final PromotionService promotionService;
  private final CorrectionService correctionService;
  private final TaskRecorder taskRecorder;
  private final CommandExecutionRepository commands;
  private final CommandProperties properties;

  /** Procesa los comandos pendientes de una issue. Best-effort: nunca relanza. */
  public void handle(final JiraIssueDto issue) {
    if (!properties.isEnabled() || issue == null || issue.getKey() == null) {
      return;
    }
    try {
      final List<JiraCommentDto> comments = jiraClient.getComments(issue.getKey());
      final List<SixaiCommand> commands = detector.detect(issue, comments);
      for (final SixaiCommand command : commands) {
        handleOne(issue, command);
      }
    } catch (RuntimeException e) {
      log.error("Error atendiendo comandos de {}: {}", issue.getKey(), e.getMessage());
    }
  }

  private void handleOne(final JiraIssueDto issue, final SixaiCommand command) {
    final CommandIntent intent = interpreter.interpret(command);
    log.info("Comando en {} de {}: intención {} — \"{}\"",
        command.issueKey(), authorLabel(command.author()), intent, command.instruction());
    recordIntent(command, intent);
    taskRecorder.record(command.issueKey(), TaskEventType.COMMAND_RECEIVED,
        authorLabel(command.author()), intent + ": " + command.instruction());

    if (properties.isDryRun()) {
      log.info("[DRY-RUN] Respondería a {} con intención {}", command.issueKey(), intent);
      return;
    }

    switch (intent) {
      case STATUS -> reply(command, statusReport(command.issueKey()));
      case PROMOTE_TO_PROD -> handlePromote(issue, command);
      case REVISE -> correctionService.requestedByHuman(command.issueKey(),
          authorLabel(command.author()), command.instruction());
      case REDEPLOY -> reply(command, "Redespliegue anotado, pero todavía no está automatizado.");
      case CANCEL -> reply(command, "Cancelación anotada. Aún no automatizo abandonar el trabajo "
          + "en curso: si hay PRs abiertas, ciérralas a mano.");
      case UNKNOWN -> reply(command, "No he entendido qué me pides. Puedes decirme, por ejemplo: "
          + "pasar a PROD, corregir algo concreto, o preguntarme cómo va.");
      default -> log.warn("Intención no contemplada: {}", intent);
    }
    recordHandled(command, intent);
  }

  private void handlePromote(final JiraIssueDto issue, final SixaiCommand command) {
    final boolean authorized = isAuthorizedToPromote(issue, command.author());
    recordAuthorization(command, authorized);
    if (!authorized) {
      log.warn("Promoción a PROD DENEGADA en {} para {}",
          command.issueKey(), authorLabel(command.author()));
      reply(command, "⛔ Solo el informador o el asignado de la tarea pueden pasarla a producción.");
      return;
    }
    log.info("Promoción a PROD AUTORIZADA en {} para {}",
        command.issueKey(), authorLabel(command.author()));
    reply(command, "🚀 Autorizado. Mergeo develop a la rama principal, compilo y despliego en "
        + "producción. Te aviso aquí cuando esté.");
    promotionService.promote(command.issueKey(), issue);
  }

  // Informador o asignado siempre; además, la lista extra de maestro.command.promote-authorized.
  private boolean isAuthorizedToPromote(final JiraIssueDto issue, final JiraUserDto author) {
    if (author == null) {
      return false;
    }
    final JiraIssueDtoFields fields = issue.getFields();
    if (fields != null
        && (sameUser(author, fields.getReporter()) || sameUser(author, fields.getAssignee()))) {
      return true;
    }
    return matchesAny(author, properties.getPromoteAuthorized());
  }

  private String statusReport(final String issueKey) {
    final SixaiSessionDto session = sessionQueryService.listSessions().stream()
        .filter(s -> issueKey.equals(s.issueKey()))
        .findFirst()
        .orElse(null);
    if (session == null || session.prs().isEmpty()) {
      return "Ahora mismo no tengo ninguna PR abierta para esta tarea.";
    }
    final StringBuilder sb = new StringBuilder("Estado de " + issueKey + ":\n");
    for (final SixaiPrDto pr : session.prs()) {
      sb.append("- ").append(pr.repo()).append(" #").append(pr.number())
          .append(" (").append(pr.branch()).append(" → ").append(pr.base()).append("): ")
          .append(pr.url()).append('\n');
    }
    return sb.toString().strip();
  }

  /** Completa el registro del comando con quién lo dio y qué se entendió. Best-effort. */
  private void recordIntent(final SixaiCommand command, final CommandIntent intent) {
    update(command, execution -> {
      execution.setIntent(intent);
      execution.setIssueStatus(command.issueStatus());
      execution.setRawText(truncate(command.instruction(), CommandExecution.RAW_TEXT_MAX));
      if (command.author() != null) {
        execution.setAuthorAccountId(command.author().getAccountId());
        execution.setAuthorName(command.author().getDisplayName());
      }
    });
  }

  /** Deja constancia de si quien pidió pasar a producción tenía permiso. */
  private void recordAuthorization(final SixaiCommand command, final boolean authorized) {
    update(command, execution -> execution.setAuthorized(authorized));
  }

  /** Cierra el registro del comando. */
  private void recordHandled(final SixaiCommand command, final CommandIntent intent) {
    update(command, execution -> {
      execution.setOutcome(intent.name());
      execution.setHandledAt(Instant.now());
    });
  }

  private void update(final SixaiCommand command, final Consumer<CommandExecution> change) {
    try {
      commands.findByCommentId(command.commentId()).ifPresent(execution -> {
        change.accept(execution);
        commands.save(execution);
      });
    } catch (RuntimeException e) {
      log.warn("No se pudo registrar el comando {}: {}", command.commentId(), e.getMessage());
    }
  }

  private static String truncate(final String value, final int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  private void reply(final SixaiCommand command, final String text) {
    try {
      jiraClient.addComment(command.issueKey(), text);
    } catch (RuntimeException e) {
      log.warn("No se pudo responder al comando de {}: {}", command.issueKey(), e.getMessage());
    }
  }

  private static String authorLabel(final JiraUserDto author) {
    if (author == null) {
      return "(desconocido)";
    }
    return author.getDisplayName() != null ? author.getDisplayName() : author.getAccountId();
  }

  private static boolean sameUser(final JiraUserDto a, final JiraUserDto b) {
    if (a == null || b == null) {
      return false;
    }
    return equalsIgnoreCase(a.getAccountId(), b.getAccountId())
        || equalsIgnoreCase(a.getEmailAddress(), b.getEmailAddress());
  }

  private static boolean matchesAny(final JiraUserDto user, final List<String> identifiers) {
    if (identifiers == null) {
      return false;
    }
    for (final String id : identifiers) {
      if (id != null && !id.isBlank()
          && (equalsIgnoreCase(user.getAccountId(), id)
              || equalsIgnoreCase(user.getEmailAddress(), id))) {
        return true;
      }
    }
    return false;
  }

  private static boolean equalsIgnoreCase(final String a, final String b) {
    return a != null && b != null && a.strip().equalsIgnoreCase(b.strip());
  }
}

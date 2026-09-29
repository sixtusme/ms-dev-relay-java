package es.colorbaby.microservices.dev.relay.control.command;

import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.ai.agent.impl.RouterAgent;
import es.colorbaby.microservices.dev.relay.config.CommandProperties;
import es.colorbaby.microservices.dev.relay.config.CorrectionProperties;
import es.colorbaby.microservices.dev.relay.control.approval.PromotionService;
import es.colorbaby.microservices.dev.relay.control.correction.CorrectionService;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Evidence;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceEntry;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceKind;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceSource;
import es.colorbaby.microservices.dev.relay.control.lifecycle.LifecycleSnapshot;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseHistoryEntry;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseOutcome;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseStatus;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Recommendation;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskLifecycle;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskPhase;
import es.colorbaby.microservices.dev.relay.delivery.pullrequest.PullRequestService;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.jira.util.JiraTextExtractor;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraCommentDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFields;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraUserDto;
import es.colorbaby.microservices.dev.relay.panel.metrics.LifecycleMetrics;
import es.colorbaby.microservices.dev.relay.panel.metrics.LifecycleMetricsService;
import es.colorbaby.microservices.dev.relay.panel.session.SessionQueryService;
import es.colorbaby.microservices.dev.relay.panel.session.SixaiPrDto;
import es.colorbaby.microservices.dev.relay.panel.session.SixaiSessionDto;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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
  private final RouterAgent routerAgent;
  private final SessionQueryService sessionQueryService;
  private final LifecycleMetricsService lifecycleMetricsService;
  private final PromotionService promotionService;
  private final CorrectionService correctionService;
  private final PullRequestService pullRequestService;
  private final TaskRecorder taskRecorder;
  private final CommandExecutionRepository commands;
  private final ProcessedCommentsTracker tracker;
  private final CommandProperties properties;
  private final CorrectionProperties correctionProperties;
  private final TaskLifecycle taskLifecycle;

  /**
   * Si la tarea está {@code BLOCKED} (el coder pidió una aclaración), atiende el siguiente
   * comentario nuevo de una persona como la respuesta — sin exigirle el prefijo {@code /sixai}:
   * es una respuesta a una pregunta directa, no una orden. No es un comando: se atiende antes y
   * aparte de {@link #handle}, con la misma idempotencia por comentario.
   */
  public void handlePendingAnswer(final JiraIssueDto issue) {
    if (issue == null || issue.getKey() == null) {
      return;
    }
    final String issueKey = issue.getKey();
    try {
      final Optional<LifecycleSnapshot> snapshot = taskLifecycle.snapshot(issueKey);
      if (snapshot.isEmpty() || snapshot.get().status() != PhaseStatus.BLOCKED) {
        return;
      }
      final PhaseHistoryEntry current = snapshot.get().current();
      final Optional<String> blockedRepo = current == null ? Optional.empty() : current.evidence().stream()
          .filter(e -> e.recommendation() == Recommendation.BLOCKED && e.repo() != null)
          .map(EvidenceEntry::repo)
          .findFirst();
      if (blockedRepo.isEmpty()) {
        return;
      }
      for (final JiraCommentDto comment : jiraClient.getComments(issueKey)) {
        if (comment.getId() == null || tracker.isBeforeWatermark(comment.getCreated())) {
          continue;
        }
        final String text = JiraTextExtractor.extractPlainText(comment.getBody());
        if (text == null || text.isBlank()) {
          continue;
        }
        if (!tracker.markIfNew(comment.getId(), issueKey)) {
          continue;
        }
        log.info("Respuesta a la pregunta pendiente de {} en {}", issueKey, blockedRepo.get());
        pullRequestService.resumeAfterAnswer(issueKey, blockedRepo.get(), text,
            outcome -> taskLifecycle.accept(issueKey, outcome));
        return;
      }
    } catch (RuntimeException e) {
      log.error("Error atendiendo la respuesta pendiente de {}: {}", issueKey, e.getMessage());
    }
  }

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
    final CommandIntent intent =
        routerAgent.route(command.issueKey(), command.issueStatus(), command.instruction());
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
      case METRICS -> reply(command, metricsReport());
      case PROMOTE_TO_PROD -> handlePromote(issue, command);
      case REVISE -> handleRevise(command);
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
    // CLIENT_TEST PASS: el cliente da el visto bueno con PROD. El check(PROMOTION) real lo hace
    // DeploymentService.startBatch, que es el único choque de PRE y PROD (ver su javadoc).
    taskLifecycle.accept(command.issueKey(), PhaseOutcome.of(TaskPhase.CLIENT_TEST,
        Recommendation.PASS).by(authorLabel(command.author())));
    promotionService.promote(command.issueKey(), issue);
  }

  private void handleRevise(final SixaiCommand command) {
    taskLifecycle.accept(command.issueKey(), PhaseOutcome.of(TaskPhase.CLIENT_TEST,
        Recommendation.FAIL,
        Evidence.of(EvidenceKind.REASON, command.instruction(), EvidenceSource.HUMAN))
        .by(authorLabel(command.author())));
    correctionService.requestedByHuman(command.issueKey(),
        authorLabel(command.author()), command.instruction());
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

  /** Fase, gate, iteración y qué falta, seguido de las PRs abiertas (como antes). */
  private String statusReport(final String issueKey) {
    final StringBuilder sb = new StringBuilder();
    taskLifecycle.snapshot(issueKey).ifPresentOrElse(
        snapshot -> sb.append(lifecycleStatus(snapshot)),
        () -> sb.append("Sin fase de ciclo de vida registrada todavía.\n"));
    sb.append(prList(issueKey));
    return sb.toString().strip();
  }

  private String lifecycleStatus(final LifecycleSnapshot snapshot) {
    if (snapshot.phase() == null) {
      return "Sin fase de ciclo de vida registrada todavía.\n";
    }
    final StringBuilder sb = new StringBuilder();
    sb.append("Fase: ").append(snapshot.phase()).append(" (").append(snapshot.status())
        .append(")\n");
    sb.append("Iteración de corrección: ").append(snapshot.iteration()).append('/')
        .append(correctionProperties.getMaxCycles()).append('\n');
    final String pending = pending(snapshot);
    if (pending != null) {
      sb.append("Falta: ").append(pending).append('\n');
    }
    final String actions = nextActions(snapshot);
    if (actions != null) {
      sb.append("Puedes: ").append(actions).append('\n');
    }
    return sb.toString();
  }

  /** Qué falta para que la fase actual cierre, si está en curso. */
  private static String pending(final LifecycleSnapshot snapshot) {
    if (snapshot.status() != PhaseStatus.IN_PROGRESS) {
      return null;
    }
    return switch (snapshot.phase()) {
      case INTAKE -> "que sixai responda y ponga la tarea en curso";
      case REPO_SELECTION -> "elegir los repos a tocar";
      case PLAN -> "que el planner clasifique la tarea y saque los criterios de aceptación";
      case IMPLEMENTATION -> "que el coder termine de escribir el código";
      case VERIFICATION -> verificationPending(snapshot);
      case APPROVAL -> "que alguien apruebe desde el panel";
      case DEPLOY_PRE -> "que termine el despliegue a PRE";
      case CLIENT_TEST -> "que el cliente confirme con PROD o pida cambios con REVISE";
      case PROMOTION -> "que termine el despliegue a producción";
      default -> null;
    };
  }

  /** Repos que ya abrieron PR en esta iteración pero todavía no tienen veredicto de compilación. */
  private static String verificationPending(final LifecycleSnapshot snapshot) {
    final PhaseHistoryEntry verification = snapshot.current();
    final PhaseHistoryEntry implementation = snapshot.history().stream()
        .filter(entry -> entry.phase() == TaskPhase.IMPLEMENTATION
            && entry.iteration() == verification.iteration())
        .reduce((first, second) -> second)
        .orElse(null);
    if (implementation == null) {
      return "el resultado de la compilación";
    }
    final Set<String> expected = implementation.evidence().stream()
        .filter(e -> e.kind() == EvidenceKind.PR && e.repo() != null)
        .map(EvidenceEntry::repo)
        .collect(Collectors.toSet());
    final Set<String> verdictGiven = verification.evidence().stream()
        .filter(e -> e.recommendation().isVerdict() && e.repo() != null)
        .map(EvidenceEntry::repo)
        .collect(Collectors.toSet());
    final long pending = expected.stream().filter(repo -> !verdictGiven.contains(repo)).count();
    return pending == 0 ? "el resultado de la compilación"
        : "el veredicto de compilación de " + pending + " de " + expected.size() + " repo(s)";
  }

  /** Qué comandos tienen sentido desde donde está la tarea ahora. */
  private static String nextActions(final LifecycleSnapshot snapshot) {
    if (snapshot.status() == PhaseStatus.FAILED) {
      return switch (snapshot.phase()) {
        case IMPLEMENTATION, VERIFICATION, DEPLOY_PRE ->
            "/sixai <qué corregir> para pedir una corrección";
        case PROMOTION -> "/sixai PROD para reintentar";
        default -> null;
      };
    }
    return snapshot.phase() == TaskPhase.CLIENT_TEST
        ? "/sixai PROD (promocionar) o /sixai <qué corregir> (pedir cambios)"
        : null;
  }

  /**
   * Métricas globales del ciclo de vida (mejoras-senior §14), no solo de esta tarea — se puede
   * preguntar desde cualquier tarea en curso.
   */
  private String metricsReport() {
    final LifecycleMetrics metrics = lifecycleMetricsService.compute();
    final StringBuilder sb = new StringBuilder("Métricas del ciclo de vida (todas las tareas):\n");
    sb.append("- Tareas: ").append(metrics.tasksStarted()).append(" arrancadas, ")
        .append(metrics.tasksCompleted()).append(" completadas, ")
        .append(metrics.tasksFailed()).append(" fallidas, ")
        .append(metrics.tasksEscalated()).append(" escaladas a una persona\n");
    sb.append("- Correcciones (manuales + automáticas): ").append(metrics.correctionCount())
        .append('\n');
    sb.append("- Avisos de ciclo de vida (LIFECYCLE_VIOLATION): ")
        .append(metrics.lifecycleViolations()).append('\n');
    sb.append("- Veces que un agente pidió aclaración: ").append(metrics.agentQuestions())
        .append('\n');
    sb.append("- Tareas que necesitaron alguna aclaración: ").append(metrics.tasksBlocked())
        .append(" de ").append(metrics.tasksStarted())
        .append(blockingRate(metrics)).append('\n');
    sb.append("- Fallos de agente (AGENT_ERROR): ").append(metrics.agentErrors()).append('\n');
    sb.append("- Decisiones registradas: ").append(metrics.humanDecisions()).append('\n');
    if (!metrics.avgPhaseDurationMs().isEmpty()) {
      sb.append("- Duración media por fase:\n");
      metrics.avgPhaseDurationMs().forEach((phase, ms) -> sb.append("  · ").append(phase)
          .append(": ").append(Duration.ofMillis(ms.longValue())).append('\n'));
    }
    return sb.toString().strip();
  }

  /** Porcentaje entre paréntesis, o vacío si no hay tareas todavía (evita dividir por cero). */
  private static String blockingRate(final LifecycleMetrics metrics) {
    if (metrics.tasksStarted() == 0) {
      return "";
    }
    return " (" + Math.round(metrics.tasksBlocked() * 100.0 / metrics.tasksStarted()) + "%)";
  }

  private String prList(final String issueKey) {
    final SixaiSessionDto session = sessionQueryService.listSessions().stream()
        .filter(s -> issueKey.equals(s.issueKey()))
        .findFirst()
        .orElse(null);
    if (session == null || session.prs().isEmpty()) {
      return "Ahora mismo no tengo ninguna PR abierta para esta tarea.";
    }
    final StringBuilder sb = new StringBuilder("PRs abiertas:\n");
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

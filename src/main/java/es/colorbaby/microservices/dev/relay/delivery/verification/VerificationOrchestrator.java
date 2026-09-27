package es.colorbaby.microservices.dev.relay.delivery.verification;

import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.ai.agent.impl.PlannerAgent;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.config.VerificationProperties;
import es.colorbaby.microservices.dev.relay.control.correction.CorrectionService;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Evidence;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceEntry;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceKind;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceSource;
import es.colorbaby.microservices.dev.relay.control.lifecycle.FailureReason;
import es.colorbaby.microservices.dev.relay.control.lifecycle.LifecycleSnapshot;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseHistoryEntry;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseOutcome;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseStatus;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Recommendation;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskLifecycle;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskPhase;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentStatus;
import es.colorbaby.microservices.dev.relay.jenkins.client.JenkinsClient;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.ai.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.ai.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.ai.llm.LlmRoles;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sigue los builds de verificación y publica el veredicto de cada PR.
 *
 * <p>Es el hermano pequeño de {@code DeploymentOrchestrator}: mismo patrón (cola → build →
 * resultado) pero sin Harbor ni despliegue, porque aquí no se publica nada, solo se pregunta si
 * compila.
 *
 * <p>El veredicto se cuenta en la tarea de Jira, no solo en el log: quien aprueba está mirando la
 * tarea o el panel, no la consola de Jenkins.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationOrchestrator {

  private static final String DIAGNOSE_SYSTEM_PROMPT =
      "Eres Sixai. Te doy la consola de un build que ha fallado al verificar una PR. Resume en "
      + "pocas frases, en español, por qué no compila y qué habría que tocar. Sé concreto y no "
      + "inventes: si la consola no basta para saberlo, dilo.";

  private final VerificationProperties properties;
  private final VerificationRunRepository runs;
  private final JenkinsClient jenkinsClient;
  private final JiraClient jiraClient;
  private final TaskRecorder taskRecorder;
  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final TaskLifecycle taskLifecycle;
  private final CorrectionService correctionService;
  private final PlannerAgent plannerAgent;

  /** Barrido periódico. El intervalo es {@code maestro.verification.poll-interval-ms}. */
  @Scheduled(fixedDelayString = "${maestro.verification.poll-interval-ms:30000}")
  public void sweep() {
    if (!properties.isEnabled()) {
      return;
    }
    final List<VerificationRun> pending = runs.findByStatus(DeploymentStatus.RUNNING);
    for (final VerificationRun run : pending) {
      try {
        advance(run);
      } catch (RuntimeException e) {
        log.warn("Fallo avanzando la verificación de {} ({}): {}",
            run.getRepo(), run.getIssueKey(), e.getMessage());
        runs.save(run);
      }
    }
  }

  private void advance(final VerificationRun run) {
    if (run.incrementAttempts() > properties.getMaxAttempts()) {
      finishFailure(run, "se agotó la espera en la etapa " + run.getStage());
      return;
    }
    final VerificationRun.Stage before = run.getStage();
    switch (before) {
      case QUEUED -> resolveQueued(run);
      case RUNNING -> onRunning(run);
      default -> log.warn("Estado no contemplado: {}", before);
    }
    if (run.getStage() != before) {
      run.resetAttempts();
    }
    if (run.getStatus() == DeploymentStatus.RUNNING) {
      runs.save(run);
    }
  }

  // La cola no es un build todavía: mientras no haya "executable", simplemente se espera.
  private void resolveQueued(final VerificationRun run) {
    final Optional<Integer> number = jenkinsClient.resolveQueuedBuild(run.getQueueUrl());
    if (number.isEmpty()) {
      return;
    }
    run.setBuildNumber(number.get());
    run.setStage(VerificationRun.Stage.RUNNING);
    log.info("Verificación de {} arrancó como build #{}", run.getRepo(), number.get());
  }

  private void onRunning(final VerificationRun run) {
    final Optional<JenkinsClient.Build> build =
        jenkinsClient.getBuild(run.getBuildJob(), run.getBuildNumber());
    if (build.isEmpty() || !build.get().finished()) {
      return;
    }
    if (build.get().success()) {
      finishSuccess(run);
      return;
    }
    finishFailure(run, "el build de la rama terminó en " + build.get().result() + diagnose(run));
  }

  private void finishSuccess(final VerificationRun run) {
    run.setStatus(DeploymentStatus.SUCCEEDED);
    runs.save(run);
    taskRecorder.record(run.getIssueKey(), TaskEventType.VERIFY_OK, "sixai",
        run.getRepo() + " #" + run.getPrNumber() + " compila");
    log.info("Verificación OK de {} #{} para {}", run.getRepo(), run.getPrNumber(),
        run.getIssueKey());
    jiraClient.addComment(run.getIssueKey(), "✅ La PR de " + run.getRepo() + " (#"
        + run.getPrNumber() + ") compila correctamente.");
    taskLifecycle.accept(run.getIssueKey(), PhaseOutcome.forRepo(TaskPhase.VERIFICATION,
        Recommendation.PASS, run.getRepo(), Evidence.of(EvidenceKind.BUILD,
            run.getBuildJob() + " #" + run.getBuildNumber() + " (PR #" + run.getPrNumber()
                + ") compila", EvidenceSource.JENKINS)));

    // Si este repo era el último que faltaba, el aggregate acaba de cerrar VERIFICATION en PASSED
    // y avanzar a APPROVAL (todo en el accept() de arriba): es el momento de comprobar, solo una
    // vez por tarea, si lo entregado cubre los criterios de PLAN (mejoras-senior §6). Si aún
    // faltan otros repos por veredicto, el aggregate sigue sin cerrar y no se comprueba nada.
    taskLifecycle.snapshot(run.getIssueKey()).ifPresent(this::checkAcceptanceCriteria);
  }

  /**
   * Comprueba si lo entregado cubre los criterios de aceptación, solo la primera vez que se llama
   * justo tras cerrar VERIFICATION en PASSED (VERIFICATION ya no es la fase actual: la tarea avanzó
   * a APPROVAL en el mismo {@code accept()} que cerró la fase, así que se busca en el historial en
   * vez de en {@link LifecycleSnapshot#current()}). Puramente informativo: nunca bloquea nada.
   */
  private void checkAcceptanceCriteria(final LifecycleSnapshot snapshot) {
    if (!properties.isCheckAcceptanceCriteria()) {
      return;
    }
    final PhaseHistoryEntry verification = lastVerification(snapshot);
    if (verification == null || verification.status() != PhaseStatus.PASSED) {
      return;
    }
    final String criteria = acceptanceCriteria(snapshot);
    if (criteria == null) {
      return;
    }
    final String changes = changesSummary(snapshot);
    if (changes.isBlank()) {
      return;
    }
    final PlannerAgent.AcceptanceCheck check =
        plannerAgent.checkAcceptance(snapshot.issueKey(), criteria, changes);
    if (check.met()) {
      return;
    }
    log.info("Los criterios de aceptación de {} no parecen cubiertos: {}", snapshot.issueKey(),
        check.explanation());
    jiraClient.addComment(snapshot.issueKey(), "⚠️ El código compila, pero puede que no cubra "
        + "todos los criterios de aceptación:\n\n" + check.explanation()
        + "\n\nRevísalo antes de aprobar; sixai no bloquea la aprobación por esto.");
    taskLifecycle.accept(snapshot.issueKey(), PhaseOutcome.of(TaskPhase.VERIFICATION,
        Recommendation.PASS, Evidence.of(EvidenceKind.REVIEW,
            "Posible criterio de aceptación no cubierto: " + check.explanation(),
            FailureReason.ACCEPTANCE_CRITERIA_FAILED, EvidenceSource.AGENT)));
  }

  private static PhaseHistoryEntry lastVerification(final LifecycleSnapshot snapshot) {
    final List<PhaseHistoryEntry> history = snapshot.history();
    for (int i = history.size() - 1; i >= 0; i--) {
      if (history.get(i).phase() == TaskPhase.VERIFICATION) {
        return history.get(i);
      }
    }
    return null;
  }

  /** Los criterios que PLAN capturó, o null si no se dedujo ninguno (nada que comprobar). */
  private static String acceptanceCriteria(final LifecycleSnapshot snapshot) {
    for (final PhaseHistoryEntry entry : snapshot.history()) {
      if (entry.phase() != TaskPhase.PLAN) {
        continue;
      }
      for (final EvidenceEntry evidence : entry.evidence()) {
        if (evidence.kind() == EvidenceKind.PLAN && evidence.detail() != null
            && evidence.detail().startsWith("Criterios de aceptación:")) {
          return evidence.detail();
        }
      }
    }
    return null;
  }

  /** Los resúmenes que el coder dejó de lo que implementó en cada repo, tal cual los escribió. */
  private static String changesSummary(final LifecycleSnapshot snapshot) {
    final StringBuilder text = new StringBuilder();
    for (final PhaseHistoryEntry entry : snapshot.history()) {
      if (entry.phase() != TaskPhase.IMPLEMENTATION) {
        continue;
      }
      for (final EvidenceEntry evidence : entry.evidence()) {
        if (evidence.kind() == EvidenceKind.REPORT && evidence.detail() != null
            && !evidence.detail().isBlank()) {
          text.append("- [").append(evidence.repo()).append("] ").append(evidence.detail())
              .append('\n');
        }
      }
    }
    return text.toString();
  }

  private void finishFailure(final VerificationRun run, final String reason) {
    run.setStatus(DeploymentStatus.FAILED);
    run.setFailureReason(reason == null || reason.length() <= VerificationRun.FAILURE_REASON_MAX
        ? reason : reason.substring(0, VerificationRun.FAILURE_REASON_MAX));
    runs.save(run);
    taskRecorder.record(run.getIssueKey(), TaskEventType.VERIFY_FAILED, "sixai",
        run.getRepo() + " #" + run.getPrNumber() + ": " + reason);
    log.warn("Verificación FALLIDA de {} #{} para {}: {}", run.getRepo(), run.getPrNumber(),
        run.getIssueKey(), reason);
    // Se avisa en la tarea, no solo en el log: quien aprueba mira la tarea y el panel, no Jenkins.
    jiraClient.addComment(run.getIssueKey(), "⚠️ La PR de " + run.getRepo() + " (#"
        + run.getPrNumber() + ") NO compila.\n\n" + reason
        + "\n\nSe puede aprobar igualmente, pero conviene mirarlo antes de mergear a develop.");
    taskLifecycle.accept(run.getIssueKey(), PhaseOutcome.forRepo(TaskPhase.VERIFICATION,
        Recommendation.FAIL, run.getRepo(),
        Evidence.of(EvidenceKind.BUILD, reason, FailureReason.BUILD_FAILED,
            EvidenceSource.JENKINS)));

    // Si este repo era el último que faltaba, el aggregate acaba de cerrar la fase en FAILED:
    // es el momento de diagnosticar y enrutar la remediación (mejoras-senior §16). Si todavía
    // faltan otros repos por dar veredicto, la fase sigue abierta y no se dispara nada aún.
    taskLifecycle.snapshot(run.getIssueKey())
        .filter(snapshot -> snapshot.phase() == TaskPhase.VERIFICATION
            && snapshot.status() == PhaseStatus.FAILED)
        .ifPresent(snapshot -> correctionService.triggeredByVerificationFailure(
            run.getIssueKey(), run.getRepo(), run.getBranch(), run.getPrNumber(),
            run.getBuildJob(), run.getBuildNumber()));
  }

  private String diagnose(final VerificationRun run) {
    if (!properties.isDiagnoseOnFailure() || !llmProperties.isEnabled()) {
      return "";
    }
    try {
      final String console = jenkinsClient.getConsoleLog(run.getBuildJob(), run.getBuildNumber());
      final String tail = tail(console, properties.getConsoleMaxChars());
      final String diagnosis = llmClient.complete(
          LlmRequest.of(DIAGNOSE_SYSTEM_PROMPT, tail, LlmRoles.DIAGNOSE, run.getIssueKey()));
      return diagnosis == null || diagnosis.isBlank()
          ? "" : "\n\nDiagnóstico de Sixai:\n" + diagnosis;
    } catch (RuntimeException e) {
      log.warn("No se pudo diagnosticar la verificación de {}: {}", run.getRepo(), e.getMessage());
      return "";
    }
  }

  /** De una consola interesa el FINAL, que es donde está el error que la tumbó. */
  private static String tail(final String value, final int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(value.length() - max);
  }
}

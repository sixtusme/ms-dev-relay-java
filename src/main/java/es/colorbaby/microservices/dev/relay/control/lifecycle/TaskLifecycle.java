package es.colorbaby.microservices.dev.relay.control.lifecycle;

import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.activity.TaskRun;
import es.colorbaby.microservices.dev.relay.activity.TaskRunRepository;
import es.colorbaby.microservices.dev.relay.config.LifecycleProperties;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El único que escribe el estado de una tarea: en qué fase está, cómo terminó cada fase y con qué
 * evidencia. Los servicios le entregan su resultado ({@link PhaseOutcome}) o le preguntan antes de
 * actuar ({@link #check}); las reglas viven en {@link LifecycleRules}.
 *
 * <p>Con {@code maestro.lifecycle.enforce=false} (modo registro) una regla incumplida se anota como
 * {@code LIFECYCLE_VIOLATION} y se deja pasar, aplicando igualmente el resultado: el estado sigue a
 * la realidad. Con {@code true}, se rechaza. La excepción es R3 (presupuesto de correcciones), que
 * frena siempre.
 *
 * <p><b>Nunca tumba el trabajo real:</b> todo va en una transacción propia y cualquier fallo se
 * loguea y se responde "permitido", igual que {@code TaskRecorder}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskLifecycle {

  private final TaskRunRepository tasks;
  private final TaskPhaseRunRepository phases;
  private final PhaseEvidenceRepository evidences;
  private final TaskRecorder taskRecorder;
  private final LifecycleRules rules;
  private final LifecycleProperties properties;
  private final PlatformTransactionManager transactionManager;

  /** Si se puede hacer algo que lleva a la tarea a {@code target}. Se llama ANTES de actuar. */
  public Verdict check(final String issueKey, final TaskPhase target) {
    return safely(issueKey, "check " + target, () -> {
      final Optional<TaskRun> task = liveTask(issueKey);
      if (task.isEmpty()) {
        return Verdict.allow();
      }
      return decide(issueKey, rules.canEnter(snapshot(task.get()), target));
    });
  }

  /** Recibe el resultado de un servicio: lo valida, guarda la evidencia y avanza si procede. */
  public Verdict accept(final String issueKey, final PhaseOutcome outcome) {
    if (outcome == null) {
      return Verdict.allow();
    }
    return safely(issueKey, "accept " + outcome.phase(), () -> {
      final Optional<TaskRun> task = liveTask(issueKey);
      if (task.isEmpty()) {
        return Verdict.allow();
      }
      final PhaseSnapshot snapshot = snapshot(task.get());
      final Verdict verdict = decide(issueKey, rules.canAccept(snapshot, outcome));
      if (verdict.denied()) {
        return verdict;
      }
      apply(task.get(), snapshot, outcome);
      return Verdict.allow();
    });
  }

  /**
   * Vuelve a implementar (ciclo de corrección): consume una iteración del presupuesto y abre
   * IMPLEMENTATION de nuevo. Con el presupuesto agotado, la tarea pasa a ESCALATED y se devuelve
   * denegado con la regla R3, en los dos modos.
   */
  public Verdict reroute(final String issueKey, final String reason, final String actor) {
    return safely(issueKey, "reroute", () -> {
      final Optional<TaskRun> live = liveTask(issueKey);
      if (live.isEmpty()) {
        return Verdict.allow();
      }
      final TaskRun task = live.get();
      final PhaseSnapshot snapshot = snapshot(task);
      final Verdict budget = rules.budget(task);
      if (budget.denied()) {
        escalate(task, snapshot);
        return budget;
      }
      final Verdict verdict = decide(issueKey, rules.canReroute(snapshot));
      if (verdict.denied()) {
        return verdict;
      }
      task.setRemediationIterations(task.getRemediationIterations() + 1);
      final TaskPhaseRun row = open(task, TaskPhase.IMPLEMENTATION);
      saveEvidence(row, PhaseOutcome.of(TaskPhase.IMPLEMENTATION, Recommendation.STARTED,
          Evidence.of(EvidenceKind.REASON, reason)).by(actor));
      return Verdict.allow();
    });
  }

  private void apply(final TaskRun task, final PhaseSnapshot snapshot,
      final PhaseOutcome outcome) {
    final TaskPhaseRun current = snapshot.current();
    if (LifecycleRules.isLate(snapshot, outcome)) {
      // Evidencia tardía de una fase ya pasada: se guarda, pero no mueve la tarea hacia atrás.
      final TaskPhaseRun past = snapshot.latest(outcome.phase());
      if (past != null) {
        saveEvidence(past, outcome);
      }
      return;
    }
    final TaskPhaseRun row = current != null && current.getPhase() == outcome.phase()
        ? current : open(task, outcome.phase());
    saveEvidence(row, outcome);

    final PhaseStatus before = row.getStatus();
    final PhaseStatus after = rules.resolve(row, outcome,
        evidences.findByTaskPhaseIdOrderByIdAsc(row.getId()), expectedRepos(snapshot, row));
    if (after == before) {
      return;
    }
    if (after.isClosed()) {
      row.close(after, outcome.actor());
    } else {
      row.reopen(after);
    }
    phases.save(row);

    if (after.letsAdvance()) {
      advance(task, row.getPhase());
    } else if (after == PhaseStatus.FAILED && row.getPhase().failureIsTerminal()) {
      terminate(task, TaskPhase.FAILED);
    }
  }

  private void advance(final TaskRun task, final TaskPhase from) {
    final TaskPhase next = from.next();
    if (next == TaskPhase.DONE) {
      terminate(task, TaskPhase.DONE);
    } else {
      open(task, next);
    }
  }

  private TaskPhaseRun open(final TaskRun task, final TaskPhase phase) {
    task.setCurrentPhase(phase.name());
    tasks.save(task);
    return phases.save(new TaskPhaseRun(task.getId(), phase, task.getRemediationIterations(),
        PhaseStatus.IN_PROGRESS));
  }

  /**
   * Lleva la tarea a un terminal. ESCALATED deja {@code task_run} en RUNNING a propósito: así sigue
   * en el panel como "Necesita una persona", igual que tras un GAVE_UP.
   */
  private void terminate(final TaskRun task, final TaskPhase terminal) {
    final PhaseStatus status = terminal == TaskPhase.DONE ? PhaseStatus.PASSED : PhaseStatus.FAILED;
    final TaskPhaseRun row =
        new TaskPhaseRun(task.getId(), terminal, task.getRemediationIterations(), status);
    row.close(status, PhaseOutcome.SIXAI);
    phases.save(row);
    task.setCurrentPhase(terminal.name());
    if (terminal != TaskPhase.ESCALATED) {
      task.close(terminal == TaskPhase.DONE ? TaskRun.DONE : TaskRun.FAILED);
    }
    tasks.save(task);
  }

  private void escalate(final TaskRun task, final PhaseSnapshot snapshot) {
    final TaskPhaseRun current = snapshot.current();
    if (current == null || current.getPhase() != TaskPhase.ESCALATED) {
      terminate(task, TaskPhase.ESCALATED);
    }
  }

  private void saveEvidence(final TaskPhaseRun row, final PhaseOutcome outcome) {
    if (outcome.evidence().isEmpty()) {
      // Sin evidencias igualmente consta la recomendación: es lo que cierra una fase por repos.
      evidences.save(new PhaseEvidence(row.getId(), outcome.repo(), outcome.recommendation(),
          EvidenceKind.REASON, null, null, outcome.actor()));
      return;
    }
    for (final Evidence item : outcome.evidence()) {
      evidences.save(new PhaseEvidence(row.getId(), outcome.repo(), outcome.recommendation(),
          item.kind(), item.detail(), item.url(), outcome.actor()));
    }
  }

  /** Repos que deben dar veredicto en VERIFICATION: los que tuvieron PR en esta iteración. */
  private Set<String> expectedRepos(final PhaseSnapshot snapshot, final TaskPhaseRun row) {
    if (row.getPhase() != TaskPhase.VERIFICATION) {
      return Set.of();
    }
    final TaskPhaseRun implementation = snapshot.latest(TaskPhase.IMPLEMENTATION);
    if (implementation == null || implementation.getIteration() != row.getIteration()) {
      return Set.of();
    }
    return evidences.findByTaskPhaseIdOrderByIdAsc(implementation.getId()).stream()
        .filter(item -> item.getKind() == EvidenceKind.PR && item.getRepo() != null)
        .map(PhaseEvidence::getRepo)
        .collect(Collectors.toSet());
  }

  /** Anota una regla incumplida y, según el modo, la hace valer o la deja pasar. */
  private Verdict decide(final String issueKey, final Verdict verdict) {
    if (verdict.allowed()) {
      return verdict;
    }
    log.warn("Ciclo de vida de {}: {} — {} ({})", issueKey, verdict.rule(), verdict.reason(),
        properties.isEnforce() ? "bloqueado" : "solo aviso");
    taskRecorder.record(issueKey, TaskEventType.LIFECYCLE_VIOLATION, PhaseOutcome.SIXAI,
        verdict.rule() + ": " + verdict.reason());
    return properties.isEnforce() ? verdict : Verdict.allow();
  }

  private Optional<TaskRun> liveTask(final String issueKey) {
    return tasks.findFirstByIssueKeyAndStatusOrderByStartedAtDesc(issueKey, TaskRun.RUNNING);
  }

  private PhaseSnapshot snapshot(final TaskRun task) {
    return PhaseSnapshot.of(phases.findByTaskRunIdOrderByIdAsc(task.getId()));
  }

  /**
   * Transacción propia y captura de errores FUERA de ella: si algo falla dentro (incluso al
   * confirmar), el llamante recibe "permitido" y sigue con su trabajo.
   *
   * <p>{@code REQUIRES_NEW} y no la propagación por defecto: varios llamantes son
   * {@code @Transactional} (el despliegue, el coordinador). Unida a la suya, un fallo aquí la
   * marcaría rollback-only y tumbaría su trabajo al confirmar, aunque aquí se capture.
   */
  private Verdict safely(final String issueKey, final String what,
      final Supplier<Verdict> work) {
    try {
      final TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      final Verdict verdict = transaction.execute(status -> work.get());
      return verdict == null ? Verdict.allow() : verdict;
    } catch (RuntimeException e) {
      log.warn("El ciclo de vida no pudo registrar {} de {}: {}", what, issueKey, e.getMessage());
      return Verdict.allow();
    }
  }
}

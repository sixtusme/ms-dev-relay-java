package es.colorbaby.microservices.dev.relay.control.lifecycle;

import es.colorbaby.microservices.dev.relay.activity.TaskRun;
import es.colorbaby.microservices.dev.relay.config.CorrectionProperties;
import es.colorbaby.microservices.dev.relay.config.VerificationProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Las reglas del ciclo de vida, sin persistencia ni efectos: dada la foto de una tarea, dicen si
 * algo se permite y en qué estado queda una fase. Decidir aquí y escribir en
 * {@link TaskLifecycle} permite leer las reglas de un vistazo.
 *
 * <p>Reglas (spec §3.3): R1 aprobación, R2 despliegues, R3 presupuesto de correcciones, R4 desde
 * dónde se corrige, R5 terminales. {@code ORDER} es un resultado fuera de secuencia.
 */
@Component
@RequiredArgsConstructor
public class LifecycleRules {

  static final String R1 = "R1";
  static final String R2 = "R2";
  static final String R3 = "R3";
  static final String R4 = "R4";
  static final String R5 = "R5";
  static final String ORDER = "ORDER";

  private final VerificationProperties verificationProperties;
  private final CorrectionProperties correctionProperties;

  /**
   * Si la tarea puede entrar (o seguir) en {@code target}. Se consulta antes de una acción con
   * efectos: aprobar, desplegar, promocionar.
   */
  public Verdict canEnter(final PhaseSnapshot snapshot, final TaskPhase target) {
    final TaskPhaseRun current = snapshot.current();
    if (current == null) {
      // Sin fase registrada no hay con qué juzgar, y el registro nunca frena el trabajo.
      return Verdict.allow();
    }
    if (current.getPhase().isTerminal()) {
      return Verdict.deny(R5, "la tarea ya terminó en " + current.getPhase());
    }
    return switch (target) {
      case APPROVAL -> canApprove(snapshot);
      case DEPLOY_PRE -> current.getPhase() == TaskPhase.DEPLOY_PRE
          && current.getStatus() == PhaseStatus.IN_PROGRESS
          ? Verdict.allow()
          : Verdict.deny(R2, "solo se despliega en PRE tras aprobar; la tarea está en "
              + describe(current));
      case PROMOTION -> current.getPhase() == TaskPhase.PROMOTION
          && (current.getStatus() == PhaseStatus.IN_PROGRESS
              || current.getStatus() == PhaseStatus.FAILED)
          ? Verdict.allow()
          : Verdict.deny(R2, "solo se promociona tras las pruebas del cliente; la tarea está en "
              + describe(current));
      default -> current.getPhase() == target
          ? Verdict.allow()
          : Verdict.deny(ORDER, "la tarea está en " + describe(current) + ", no en " + target);
    };
  }

  /** Si se puede aceptar este resultado con la tarea donde está. */
  public Verdict canAccept(final PhaseSnapshot snapshot, final PhaseOutcome outcome) {
    final TaskPhaseRun current = snapshot.current();
    if (current == null) {
      return outcome.phase() == TaskPhase.INTAKE
          ? Verdict.allow()
          : Verdict.deny(ORDER, "la tarea no tiene fase registrada y llega un resultado de "
              + outcome.phase());
    }
    if (current.getPhase().isTerminal()) {
      return Verdict.deny(R5, "la tarea ya terminó en " + current.getPhase());
    }
    if (outcome.phase() == current.getPhase()) {
      if (!current.getStatus().isClosed() || !outcome.isAggregate()
          || isPromotionRetry(current, outcome)) {
        return Verdict.allow();
      }
      return Verdict.deny(ORDER, outcome.phase() + " ya estaba cerrada en " + current.getStatus());
    }
    if (isLate(snapshot, outcome)) {
      // Evidencia tardía de una fase ya pasada: se guarda y no mueve la tarea.
      return Verdict.allow();
    }
    // Salto adelante: solo si la regla de esa fase lo permite (p. ej. aprobar con la verificación
    // fallida cuando block-approval está apagado).
    return canEnter(snapshot, outcome.phase());
  }

  /** R4/R5: si desde donde está la tarea se puede volver a implementar. */
  public Verdict canReroute(final PhaseSnapshot snapshot) {
    final TaskPhaseRun current = snapshot.current();
    if (current == null) {
      return Verdict.allow();
    }
    if (current.getPhase().isTerminal()) {
      return Verdict.deny(R5, "la tarea ya terminó en " + current.getPhase());
    }
    final boolean failed = current.getStatus() == PhaseStatus.FAILED;
    final boolean allowed = switch (current.getPhase()) {
      case IMPLEMENTATION, VERIFICATION, DEPLOY_PRE -> failed;
      case CLIENT_TEST -> true;
      default -> false;
    };
    return allowed
        ? Verdict.allow()
        : Verdict.deny(R4, "no se corrige desde " + describe(current)
            + "; solo desde una implementación, verificación o despliegue a PRE fallidos, o "
            + "desde las pruebas del cliente");
  }

  /**
   * R3: presupuesto de correcciones. Es vinculante también en modo registro: sustituye a un tope
   * que ya frenaba antes, y sin él la corrección podría girar sin fin.
   */
  public Verdict budget(final TaskRun task) {
    final int max = correctionProperties.getMaxCycles();
    return task.getRemediationIterations() >= max
        ? Verdict.deny(R3, "agotados los " + max + " ciclos de corrección")
        : Verdict.allow();
  }

  /**
   * Estado de la fase tras recibir un resultado. Uno agregado lo decide por sí solo; uno por repo
   * solo cierra VERIFICATION, cuando todos los repos esperados tienen veredicto.
   *
   * @param evidence      evidencias de la fila, ya incluida la del resultado recibido
   * @param expectedRepos repos que deben dar veredicto (solo se usa en VERIFICATION)
   */
  public PhaseStatus resolve(final TaskPhaseRun row, final PhaseOutcome outcome,
      final List<PhaseEvidence> evidence, final Set<String> expectedRepos) {
    if (isPromotionRetry(row, outcome)) {
      return PhaseStatus.IN_PROGRESS;
    }
    if (row.getStatus().isClosed()) {
      return row.getStatus();
    }
    if (outcome.isAggregate()) {
      return outcome.recommendation().toStatus();
    }
    if (row.getPhase() != TaskPhase.VERIFICATION) {
      return row.getStatus();
    }
    return aggregateByRepo(evidence, expectedRepos, row.getStatus());
  }

  /**
   * Si el resultado es de una fase que la tarea ya dejó atrás: anterior en el recorrido, o de una
   * iteración anterior a la actual. Lo segundo importa tras una corrección: la tarea vuelve a
   * IMPLEMENTATION (n+1), y un veredicto o un fallo de despliegue que llegue tarde de la iteración
   * n no es un salto adelante, es historia.
   */
  static boolean isLate(final PhaseSnapshot snapshot, final PhaseOutcome outcome) {
    final TaskPhaseRun current = snapshot.current();
    if (current == null || outcome.phase() == current.getPhase()) {
      return false;
    }
    if (outcome.phase().isBefore(current.getPhase())) {
      return true;
    }
    final TaskPhaseRun past = snapshot.latest(outcome.phase());
    return past != null && past.getIteration() < current.getIteration();
  }

  /** Un {@code PROMOTE} nuevo tras una promoción fallida: la fase se reabre. */
  static boolean isPromotionRetry(final TaskPhaseRun row, final PhaseOutcome outcome) {
    return row.getPhase() == TaskPhase.PROMOTION
        && row.getStatus() == PhaseStatus.FAILED
        && outcome.phase() == TaskPhase.PROMOTION
        && outcome.isAggregate()
        && outcome.recommendation() == Recommendation.STARTED;
  }

  private Verdict canApprove(final PhaseSnapshot snapshot) {
    final TaskPhaseRun implementation = snapshot.latest(TaskPhase.IMPLEMENTATION);
    if (implementation == null || implementation.getStatus() != PhaseStatus.PASSED) {
      return Verdict.deny(R1, "no hay código que aprobar: la implementación está en "
          + describe(implementation));
    }
    final TaskPhaseRun current = snapshot.current();
    if (current.getPhase() == TaskPhase.APPROVAL) {
      return Verdict.allow();
    }
    if (current.getPhase() == TaskPhase.VERIFICATION
        && current.getStatus() == PhaseStatus.FAILED) {
      return verificationProperties.isBlockApproval()
          ? Verdict.deny(R1, "alguna PR no compila y block-approval está activo")
          : Verdict.allow();
    }
    return Verdict.deny(R1, "la tarea está en " + describe(current)
        + "; solo se aprueba con la verificación cerrada");
  }

  /** Último veredicto de cada repo; la fase se cierra cuando están todos los esperados. */
  private static PhaseStatus aggregateByRepo(final List<PhaseEvidence> evidence,
      final Set<String> expectedRepos, final PhaseStatus unchanged) {
    if (expectedRepos.isEmpty()) {
      return unchanged;
    }
    final Map<String, Recommendation> verdicts = new HashMap<>();
    for (final PhaseEvidence item : evidence) {
      if (item.getRepo() != null && item.getRecommendation().isVerdict()) {
        verdicts.put(item.getRepo(), item.getRecommendation());
      }
    }
    if (!verdicts.keySet().containsAll(expectedRepos)) {
      return unchanged;
    }
    if (expectedRepos.stream().anyMatch(repo -> verdicts.get(repo) == Recommendation.FAIL)) {
      return PhaseStatus.FAILED;
    }
    return expectedRepos.stream().allMatch(repo -> verdicts.get(repo) == Recommendation.SKIPPED)
        ? PhaseStatus.SKIPPED : PhaseStatus.PASSED;
  }

  private static String describe(final TaskPhaseRun row) {
    return row == null ? "(sin registrar)" : row.getPhase() + " (" + row.getStatus() + ")";
  }
}

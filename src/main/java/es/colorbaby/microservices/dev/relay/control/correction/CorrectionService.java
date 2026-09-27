package es.colorbaby.microservices.dev.relay.control.correction;

import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.ai.agent.impl.DiagnosticianAgent;
import es.colorbaby.microservices.dev.relay.ai.agent.impl.DiagnosticianAgent.RootCause;
import es.colorbaby.microservices.dev.relay.config.CorrectionProperties;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Evidence;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceKind;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceSource;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseOutcome;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Recommendation;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskLifecycle;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskPhase;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Verdict;
import es.colorbaby.microservices.dev.relay.delivery.pullrequest.PullRequestService;
import es.colorbaby.microservices.dev.relay.delivery.verification.VerificationService;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Cierra el bucle: cuando el cliente dice que algo está mal, cuando un build/despliegue falla, o
 * cuando falla la verificación de una PR, sixai vuelve a ponerse a trabajar.
 *
 * <p>Un fallo de {@code VERIFICATION} reintenta sobre la MISMA PR ({@link #triggeredByVerificationFailure}):
 * la rama todavía no se ha mergeado, así que GitHub actualiza la PR sola en cuanto llega un commit
 * nuevo — no hace falta abrir otra. Una corrección pedida a mano o disparada por un despliegue
 * fallido ({@link #requestedByHuman}/{@link #triggeredByFailure}) sigue abriendo PRs <b>nuevas</b>:
 * en esos casos la PR anterior ya se mergeó a develop, así que no hay rama abierta a la que volver.
 * Todos los caminos pasan por la aprobación humana → PRE → TEST de siempre.
 *
 * <p><b>Los frenos son lo importante de esta pieza.</b> Un agente que reintenta arreglar y volver a
 * desplegar puede quemar CI o dejar las cosas peor, así que:
 * <ul>
 *   <li>hay un <b>tope de ciclos por tarea</b>, que ahora impone {@link TaskLifecycle#reroute}
 *       (regla R3, vinculante en los dos modos) — el mismo presupuesto para las correcciones
 *       manuales y para las automáticas: no hay un límite aparte para estas últimas, y así una
 *       causa que se resiste no puede consumir CI sin fin solo porque nadie la pidió a mano; al
 *       agotarse, sixai <b>para y escala a una persona</b> en vez de seguir insistiendo;</li>
 *   <li>cada ciclo pasa por la <b>aprobación humana</b> del front, que es el freno natural: nada
 *       llega a PRE sin que alguien lo mire.</li>
 * </ul>
 *
 * <p>Cuando la causa es un fallo de {@code VERIFICATION}, antes de reintentar se clasifica con
 * {@link DiagnosticianAgent} y se enruta (mejoras-senior §16): {@code CODE} al coder, {@code PLAN}
 * al planner primero, {@code CRITERIA} a una persona (sin gastar presupuesto) y {@code INFRA} a un
 * reintento sin tocar código.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorrectionService {

  private final PullRequestService pullRequestService;
  private final VerificationService verificationService;
  private final DiagnosticianAgent diagnosticianAgent;
  private final JiraClient jiraClient;
  private final TaskRecorder taskRecorder;
  private final CorrectionProperties properties;
  private final TaskLifecycle taskLifecycle;

  /** Corrección pedida por una persona con {@code /sixai …}. */
  public void requestedByHuman(final String issueKey, final String actor,
      final String instruction) {
    attempt(issueKey, actor, instruction,
        "He anotado lo que hay que corregir y me pongo con ello.", EvidenceSource.HUMAN);
  }

  /** Corrección disparada por un fallo de build o despliegue. */
  public void triggeredByFailure(final String issueKey, final String reason) {
    if (!properties.isAutoFixOnFailure()) {
      return;
    }
    attempt(issueKey, "sixai", "El despliegue falló:\n" + reason
            + "\n\nCorrige la causa del fallo.",
        "Voy a intentar corregir la causa del fallo.", EvidenceSource.SYSTEM);
  }

  /**
   * Disparada cuando {@code VERIFICATION} cierra en {@code FAILED} (el sweeper de Jenkins es quien
   * la llama, en cuanto ve que era el último repo que faltaba). Diagnostica y enruta antes de
   * decidir qué hacer, en vez de reintentar a ciegas siempre con el coder.
   */
  public void triggeredByVerificationFailure(final String issueKey, final String repo,
      final String branch, final int prNumber, final String buildJob, final int buildNumber) {
    if (!properties.isEnabled() || !properties.isAutoFixOnFailure()) {
      return;
    }
    final RootCause cause = diagnosticianAgent.classifyVerificationFailure(buildJob, buildNumber, issueKey);
    log.info("Causa raíz del fallo de {} en {}: {} — {}",
        repo, issueKey, cause.category(), cause.explanation());
    switch (cause.category()) {
      case CODE -> retryWithCoder(issueKey, repo, branch, prNumber, cause, false);
      case PLAN -> retryWithCoder(issueKey, repo, branch, prNumber, cause, true);
      case INFRA -> retryInfra(issueKey, repo, branch, prNumber, cause);
      case CRITERIA -> escalateToHuman(issueKey, repo, prNumber, cause);
    }
  }

  /** CODE (replan=false) o PLAN (replan=true): reroute + coder sobre la misma PR. */
  private void retryWithCoder(final String issueKey, final String repo, final String branch,
      final int prNumber, final RootCause cause, final boolean replan) {
    if (properties.isDryRun()) {
      log.info("[DRY-RUN] {} {} #{} en {}: {}", replan ? "Replantearía" : "Corregiría", repo,
          prNumber, issueKey, cause.explanation());
      return;
    }
    final String reason = (replan ? "replantear el enfoque" : "corregir el código")
        + " tras un fallo de compilación: " + cause.explanation();
    final Verdict verdict = taskLifecycle.reroute(issueKey, reason, "sixai", EvidenceSource.SYSTEM);
    if (verdict.denied()) {
      giveUp(issueKey, verdict.reason());
      return;
    }
    taskRecorder.record(issueKey, TaskEventType.CORRECTION_STARTED, "sixai", reason);
    comment(issueKey, (replan ? "🔁 Replanteo" : "🔧 Corrijo") + " la PR de " + repo + " (#"
        + prNumber + ") tras el fallo: " + cause.explanation());
    pullRequestService.retryOnSameBranch(issueKey, repo, branch, prNumber, cause.explanation(),
        replan, outcome -> taskLifecycle.accept(issueKey, outcome));
  }

  /**
   * INFRA: no es el código, así que no se toca — se cierra IMPLEMENTATION tal cual (misma PR) y se
   * vuelve a pedir el build. Consume presupuesto igual que las demás rutas: un runner que falla
   * una y otra vez no debe poder reintentar sin límite solo porque "no es culpa del código".
   */
  private void retryInfra(final String issueKey, final String repo, final String branch,
      final int prNumber, final RootCause cause) {
    if (properties.isDryRun()) {
      log.info("[DRY-RUN] Reintentaría {} #{} en {} (infraestructura): {}",
          repo, prNumber, issueKey, cause.explanation());
      return;
    }
    final String reason = "reintento por infraestructura: " + cause.explanation();
    final Verdict verdict = taskLifecycle.reroute(issueKey, reason, "sixai", EvidenceSource.SYSTEM);
    if (verdict.denied()) {
      giveUp(issueKey, verdict.reason());
      return;
    }
    taskRecorder.record(issueKey, TaskEventType.CORRECTION_STARTED, "sixai", reason);
    comment(issueKey, "🔁 El fallo de " + repo + " (#" + prNumber + ") parece de infraestructura, "
        + "no del código: " + cause.explanation() + "\n\nVuelvo a compilar sin tocar nada.");
    taskLifecycle.accept(issueKey, PhaseOutcome.forRepo(TaskPhase.IMPLEMENTATION,
        Recommendation.PASS, repo, Evidence.of(EvidenceKind.DECISION,
            "misma PR, sin cambios de código (reintento de infraestructura)", EvidenceSource.SYSTEM)));
    taskLifecycle.accept(issueKey, PhaseOutcome.of(TaskPhase.IMPLEMENTATION, Recommendation.PASS,
        Evidence.of(EvidenceKind.REASON, "reintento de infraestructura en " + repo,
            EvidenceSource.SYSTEM)));
    taskLifecycle.accept(issueKey, verificationService.verify(issueKey, repo, branch, prNumber));
  }

  /** CRITERIA: no se gasta presupuesto pidiendo una decisión — solo se informa. */
  private void escalateToHuman(final String issueKey, final String repo, final int prNumber,
      final RootCause cause) {
    comment(issueKey, "❓ La PR de " + repo + " (#" + prNumber + ") no compila y parece necesitar "
        + "una decisión: " + cause.explanation()
        + "\n\nCuéntame qué hacer, o corrige el código tú mismo y sixai vuelve a comprobarlo.");
  }

  private void attempt(final String issueKey, final String actor, final String instruction,
      final String ack, final EvidenceSource source) {
    if (!properties.isEnabled()) {
      return;
    }
    if (instruction == null || instruction.isBlank()) {
      return;
    }
    taskRecorder.record(issueKey, TaskEventType.CORRECTION_REQUESTED, actor, instruction);

    if (properties.isDryRun()) {
      log.info("[DRY-RUN] Corregiría {}: {}", issueKey, instruction);
      return;
    }

    // reroute ANTES de abrir PRs: consume el presupuesto y abre IMPLEMENTATION (iteración + 1) de
    // una vez, para que los resultados de openForIssue lleguen con la fase ya correcta.
    final Verdict verdict = taskLifecycle.reroute(issueKey, instruction, actor, source);
    if (verdict.denied()) {
      // Hoy (modo registro) solo puede denegar por R3 (presupuesto agotado): la regla R4 (desde
      // dónde se corrige) solo frena de verdad en modo estricto, que todavía no está activado.
      giveUp(issueKey, verdict.reason());
      return;
    }

    log.info("Ciclo de corrección para {}", issueKey);
    taskRecorder.record(issueKey, TaskEventType.CORRECTION_STARTED, "sixai", instruction);
    comment(issueKey, ack);
    pullRequestService.openForIssue(issueKey, instruction,
        outcome -> taskLifecycle.accept(issueKey, outcome));
  }

  /**
   * Se para y se avisa. Es deliberado: insistir más veces sobre algo que no mejora quema CI y puede
   * dejar el servicio peor de lo que estaba. La tarea ya quedó en ESCALATED (lo hizo
   * {@link TaskLifecycle#reroute} al denegar).
   */
  private void giveUp(final String issueKey, final String reason) {
    log.warn("Ciclo de vida escala {} a una persona: {}", issueKey, reason);
    taskRecorder.record(issueKey, TaskEventType.GAVE_UP, "sixai", reason);
    comment(issueKey, "🛑 " + reason + ". Paro aquí para no seguir dando vueltas: necesita que "
        + "alguien le eche un vistazo.");
  }

  private void comment(final String issueKey, final String text) {
    try {
      jiraClient.addComment(issueKey, text);
    } catch (RuntimeException e) {
      log.warn("No se pudo comentar en {}: {}", issueKey, e.getMessage());
    }
  }
}

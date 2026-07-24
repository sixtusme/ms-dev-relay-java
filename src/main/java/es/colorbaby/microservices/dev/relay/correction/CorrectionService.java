package es.colorbaby.microservices.dev.relay.correction;

import es.colorbaby.microservices.dev.relay.activity.TaskEvent;
import es.colorbaby.microservices.dev.relay.activity.TaskEventRepository;
import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.config.CorrectionProperties;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.pullrequest.PullRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Cierra el bucle: cuando el cliente dice que algo está mal, o cuando un build/despliegue falla,
 * sixai vuelve a ponerse a trabajar. Cada intento abre <b>PRs nuevas</b> (las anteriores ya se
 * mergearon a develop) y vuelve a pasar por la aprobación humana → PRE → TEST.
 *
 * <p><b>Los frenos son lo importante de esta pieza.</b> Un agente que reintenta arreglar y volver a
 * desplegar puede quemar CI o dejar las cosas peor, así que:
 * <ul>
 *   <li>hay un <b>tope de ciclos por tarea</b>; al agotarse, sixai <b>para y escala a una
 *       persona</b> en vez de seguir insistiendo;</li>
 *   <li>cada ciclo pasa por la <b>aprobación humana</b> del front, que es el freno natural: nada
 *       llega a PRE sin que alguien lo mire.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorrectionService {

  private final PullRequestService pullRequestService;
  private final JiraClient jiraClient;
  private final TaskRecorder taskRecorder;
  private final TaskEventRepository events;
  private final CorrectionProperties properties;

  /** Corrección pedida por una persona con {@code /sixai …}. */
  public void requestedByHuman(final String issueKey, final String actor,
      final String instruction) {
    attempt(issueKey, actor, instruction,
        "He anotado lo que hay que corregir y me pongo con ello.");
  }

  /** Corrección disparada por un fallo de build o despliegue. */
  public void triggeredByFailure(final String issueKey, final String reason) {
    if (!properties.isAutoFixOnFailure()) {
      return;
    }
    attempt(issueKey, "sixai", "El despliegue falló:\n" + reason
            + "\n\nCorrige la causa del fallo.",
        "Voy a intentar corregir la causa del fallo.");
  }

  private void attempt(final String issueKey, final String actor, final String instruction,
      final String ack) {
    if (!properties.isEnabled()) {
      return;
    }
    if (instruction == null || instruction.isBlank()) {
      return;
    }
    taskRecorder.record(issueKey, TaskEventType.CORRECTION_REQUESTED, actor, instruction);

    final long cycles = countCycles(issueKey);
    if (cycles >= properties.getMaxCycles()) {
      giveUp(issueKey, cycles);
      return;
    }

    if (properties.isDryRun()) {
      log.info("[DRY-RUN] Corregiría {} (ciclo {}): {}", issueKey, cycles + 1, instruction);
      return;
    }

    log.info("Ciclo de corrección {} de {} para {}", cycles + 1, properties.getMaxCycles(),
        issueKey);
    taskRecorder.record(issueKey, TaskEventType.CORRECTION_STARTED, "sixai",
        "ciclo " + (cycles + 1) + " de " + properties.getMaxCycles());
    comment(issueKey, ack);
    pullRequestService.openForIssue(issueKey, instruction);
  }

  /**
   * Se para y se avisa. Es deliberado: insistir más veces sobre algo que no mejora quema CI y puede
   * dejar el servicio peor de lo que estaba.
   */
  private void giveUp(final String issueKey, final long cycles) {
    log.warn("Agotados los {} ciclos de corrección de {}; escalo a una persona", cycles, issueKey);
    taskRecorder.record(issueKey, TaskEventType.GAVE_UP, "sixai",
        "agotados " + cycles + " ciclos de corrección");
    comment(issueKey, "🛑 Llevo " + cycles + " intentos de corrección en esta tarea y no lo estoy "
        + "resolviendo. Paro aquí para no seguir dando vueltas: necesita que alguien le eche un "
        + "vistazo.");
  }

  /** Ciclos ya intentados en esta tarea, contados sobre la propia línea de tiempo. */
  private long countCycles(final String issueKey) {
    try {
      return events.findByIssueKeyOrderByOccurredAtAsc(issueKey).stream()
          .map(TaskEvent::getType)
          .filter(TaskEventType.CORRECTION_STARTED::equals)
          .count();
    } catch (RuntimeException e) {
      log.warn("No se pudieron contar los ciclos de {}: {}", issueKey, e.getMessage());
      return 0;
    }
  }

  private void comment(final String issueKey, final String text) {
    try {
      jiraClient.addComment(issueKey, text);
    } catch (RuntimeException e) {
      log.warn("No se pudo comentar en {}: {}", issueKey, e.getMessage());
    }
  }
}

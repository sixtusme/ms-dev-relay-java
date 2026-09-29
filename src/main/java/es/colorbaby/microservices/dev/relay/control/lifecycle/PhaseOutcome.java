package es.colorbaby.microservices.dev.relay.control.lifecycle;

import java.util.List;
import java.util.Objects;

/**
 * El handoff: lo que un servicio devuelve al terminar su parte de una fase. No toca el estado; se
 * lo entrega a {@link TaskLifecycle#accept}, que es quien decide.
 *
 * @param repo  repo al que se refiere, o null si es el resultado agregado de la fase (el que la
 *              cierra, salvo en VERIFICATION, que se cierra al tener veredicto de todos los repos)
 * @param actor quién lo decide: {@value #SIXAI} o la persona (el aprobador, el autor del comando)
 */
public record PhaseOutcome(TaskPhase phase, Recommendation recommendation, String repo,
    List<Evidence> evidence, String actor) {

  public static final String SIXAI = "sixai";

  public PhaseOutcome {
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(recommendation, "recommendation");
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
    actor = actor == null || actor.isBlank() ? SIXAI : actor;
  }

  /** Resultado agregado de la fase. */
  public static PhaseOutcome of(final TaskPhase phase, final Recommendation recommendation,
      final Evidence... evidence) {
    return new PhaseOutcome(phase, recommendation, null, List.of(evidence), SIXAI);
  }

  /** Resultado de un solo repo dentro de la fase. */
  public static PhaseOutcome forRepo(final TaskPhase phase, final Recommendation recommendation,
      final String repo, final Evidence... evidence) {
    return new PhaseOutcome(phase, recommendation, repo, List.of(evidence), SIXAI);
  }

  /** El mismo resultado, decidido por otra persona. */
  public PhaseOutcome by(final String who) {
    return new PhaseOutcome(phase, recommendation, repo, evidence, who);
  }

  public boolean isAggregate() {
    return repo == null;
  }
}

package es.colorbaby.microservices.dev.relay.control.lifecycle;

import java.time.Instant;
import java.util.List;

/** Una pasada de la tarea por una fase, con su evidencia. Ver {@link LifecycleSnapshot}. */
public record PhaseHistoryEntry(TaskPhase phase, int iteration, PhaseStatus status,
    String decidedBy, Instant startedAt, Instant finishedAt, List<EvidenceEntry> evidence) {
}

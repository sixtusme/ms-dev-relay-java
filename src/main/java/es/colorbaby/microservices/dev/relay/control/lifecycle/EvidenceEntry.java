package es.colorbaby.microservices.dev.relay.control.lifecycle;

import java.time.Instant;

/** Una evidencia ya guardada, de solo lectura. Ver {@link LifecycleSnapshot}. */
public record EvidenceEntry(String repo, Recommendation recommendation, EvidenceKind kind,
    String detail, String url, String actor, FailureReason reasonCode, EvidenceSource source,
    Instant recordedAt) {

  public static EvidenceEntry of(final PhaseEvidence entity) {
    return new EvidenceEntry(entity.getRepo(), entity.getRecommendation(), entity.getKind(),
        entity.getDetail(), entity.getUrl(), entity.getActor(), entity.getReasonCode(),
        entity.getSource(), entity.getRecordedAt());
  }
}

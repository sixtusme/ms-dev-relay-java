package es.colorbaby.microservices.dev.relay.control.lifecycle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Una evidencia de un resultado entregado sobre una fase. Append-only: nunca se modifica. */
@Entity
@Table(name = "task_phase_evidence")
@Getter
@NoArgsConstructor
public class PhaseEvidence {

  /** Topes; deben coincidir con las columnas. */
  public static final int DETAIL_MAX = 2000;
  public static final int URL_MAX = 500;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_phase_id", nullable = false)
  private Long taskPhaseId;

  /** Repo al que se refiere; null si es el resultado agregado de la fase. */
  private String repo;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Recommendation recommendation;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private EvidenceKind kind;

  @Column(length = DETAIL_MAX)
  private String detail;

  @Column(length = URL_MAX)
  private String url;

  private String actor;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt = Instant.now();

  public PhaseEvidence(final Long taskPhaseId, final String repo,
      final Recommendation recommendation, final EvidenceKind kind, final String detail,
      final String url, final String actor) {
    this.taskPhaseId = taskPhaseId;
    this.repo = repo;
    this.recommendation = recommendation;
    this.kind = kind;
    this.detail = truncate(detail, DETAIL_MAX);
    this.url = truncate(url, URL_MAX);
    this.actor = actor;
  }

  private static String truncate(final String value, final int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}

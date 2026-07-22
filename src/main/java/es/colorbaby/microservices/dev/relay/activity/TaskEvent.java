package es.colorbaby.microservices.dev.relay.activity;

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
import lombok.Setter;

/** Un hito en la vida de una tarea. Append-only: se añade, nunca se modifica. */
@Entity
@Table(name = "task_event")
@Getter
@Setter
@NoArgsConstructor
public class TaskEvent {

  /** Tope del detalle; debe coincidir con la columna. */
  public static final int DETAIL_MAX = 2000;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_run_id")
  private Long taskRunId;

  @Column(name = "issue_key", nullable = false)
  private String issueKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TaskEventType type;

  /** Quién o qué lo provocó: una persona, "sixai", o un job. */
  private String actor;

  @Column(length = DETAIL_MAX)
  private String detail;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt = Instant.now();

  public TaskEvent(final Long taskRunId, final String issueKey, final TaskEventType type,
      final String actor, final String detail) {
    this.taskRunId = taskRunId;
    this.issueKey = issueKey;
    this.type = type;
    this.actor = actor;
    this.detail = detail;
  }
}

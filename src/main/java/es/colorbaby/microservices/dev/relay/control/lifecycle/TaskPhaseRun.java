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
import lombok.Setter;

/**
 * Una pasada de una tarea por una fase. Una corrección no la sobrescribe: abre otra con la
 * iteración siguiente, así queda el historial de cada intento. La fase actual de la tarea es su
 * última fila.
 */
@Entity
@Table(name = "task_phase")
@Getter
@Setter
@NoArgsConstructor
public class TaskPhaseRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_run_id", nullable = false)
  private Long taskRunId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TaskPhase phase;

  @Column(nullable = false)
  private int iteration;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PhaseStatus status;

  /** Quién cerró el gate: "sixai" o una persona. */
  @Column(name = "decided_by")
  private String decidedBy;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt = Instant.now();

  @Column(name = "finished_at")
  private Instant finishedAt;

  public TaskPhaseRun(final Long taskRunId, final TaskPhase phase, final int iteration,
      final PhaseStatus status) {
    this.taskRunId = taskRunId;
    this.phase = phase;
    this.iteration = iteration;
    this.status = status;
  }

  /** Cierra el gate con su estado final y quién lo decidió. */
  public void close(final PhaseStatus finalStatus, final String actor) {
    this.status = finalStatus;
    this.decidedBy = actor;
    this.finishedAt = Instant.now();
  }

  /** Vuelve a dejar la fase abierta (reintento de la promoción, o un estado no final). */
  public void reopen(final PhaseStatus openStatus) {
    this.status = openStatus;
    this.decidedBy = null;
    this.finishedAt = null;
  }
}

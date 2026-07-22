package es.colorbaby.microservices.dev.relay.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Una tarea que sixai ha cogido: qué es, quién la mandó, cuándo empezó y cuánto tardó. Es el eje
 * del que cuelgan los eventos, las llamadas al modelo y los despliegues.
 *
 * <p>{@code durationMs} está desnormalizado a propósito: es la pregunta más frecuente ("¿cuánto
 * tardó?") y así no hay que recalcularla recorriendo la línea de tiempo.
 */
@Entity
@Table(name = "task_run")
@Getter
@Setter
@NoArgsConstructor
public class TaskRun {

  /** Estados de una tarea desde el punto de vista de sixai. */
  public static final String RUNNING = "RUNNING";
  public static final String DONE = "DONE";
  public static final String FAILED = "FAILED";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "issue_key", nullable = false)
  private String issueKey;

  private String title;

  private String epic;

  /** Sistema al que pertenece (pim, docs, b2b2c…), según el mapeo de repos. */
  @Column(name = "system_name")
  private String systemName;

  /** Quién la mandó: autor del comentario que disparó la tarea. */
  @Column(name = "requested_by_account_id")
  private String requestedByAccountId;

  @Column(name = "requested_by_name")
  private String requestedByName;

  /** Cómo se detectó: POLLING o WEBHOOK. */
  @Column(name = "trigger_source")
  private String triggerSource;

  @Column(nullable = false)
  private String status = RUNNING;

  @Column(name = "current_phase")
  private String currentPhase;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt = Instant.now();

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(name = "duration_ms")
  private Long durationMs;

  public TaskRun(final String issueKey, final String requestedByAccountId,
      final String requestedByName, final String triggerSource) {
    this.issueKey = issueKey;
    this.requestedByAccountId = requestedByAccountId;
    this.requestedByName = requestedByName;
    this.triggerSource = triggerSource;
  }
}

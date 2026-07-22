package es.colorbaby.microservices.dev.relay.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Una llamada al modelo. Permite responder qué rol consume más, cuál se ha vuelto lento y cuánto
 * cuesta una tarea. Se registra siempre, apagado o no el gateway de modelos.
 *
 * <p>Los tokens y el coste quedan a null de momento: el cliente OpenAI-compatible aún no expone el
 * bloque {@code usage} de la respuesta. La latencia, el rol y el desenlace sí se registran.
 */
@Entity
@Table(name = "llm_call")
@Getter
@Setter
@NoArgsConstructor
public class LlmCall {

  /** Tope del error guardado; debe coincidir con la columna. */
  public static final int ERROR_MAX = 1000;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_run_id")
  private Long taskRunId;

  @Column(name = "issue_key")
  private String issueKey;

  /** responder, selector, coder, router, diagnose… */
  @Column(nullable = false)
  private String role;

  private String model;

  @Column(name = "prompt_tokens")
  private Integer promptTokens;

  @Column(name = "completion_tokens")
  private Integer completionTokens;

  @Column(name = "total_tokens")
  private Integer totalTokens;

  @Column(name = "cost_usd")
  private BigDecimal costUsd;

  @Column(name = "latency_ms")
  private Long latencyMs;

  @Column(nullable = false)
  private boolean success = true;

  @Column(length = ERROR_MAX)
  private String error;

  @Column(name = "called_at", nullable = false)
  private Instant calledAt = Instant.now();
}

package es.colorbaby.microservices.dev.relay.command;

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
 * Un comando {@code /sixai} recibido. Hace dos trabajos a la vez, y por eso no hay dos tablas:
 *
 * <ul>
 *   <li><b>Idempotencia</b>: {@code comment_id} es único, así que insertarlo es la forma atómica de
 *       decidir si esta orden ya se atendió. Persistido, sobrevive a reinicios.</li>
 *   <li><b>Historial</b>: guarda la orden tal cual se escribió, la intención resuelta, si quien la
 *       dio tenía permiso y cómo acabó. Es la materia prima para afinar el intérprete y para
 *       responder "quién mandó qué y cuándo".</li>
 * </ul>
 */
@Entity
@Table(name = "command_execution")
@Getter
@Setter
@NoArgsConstructor
public class CommandExecution {

  /** Tope de la orden guardada; debe coincidir con la columna. */
  public static final int RAW_TEXT_MAX = 2000;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "comment_id", nullable = false, unique = true)
  private String commentId;

  @Column(name = "issue_key", nullable = false)
  private String issueKey;

  @Column(name = "author_account_id")
  private String authorAccountId;

  @Column(name = "author_name")
  private String authorName;

  /** La orden tal cual la escribió la persona. */
  @Column(name = "raw_text", length = RAW_TEXT_MAX)
  private String rawText;

  @Column(name = "issue_status")
  private String issueStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "intent")
  private CommandIntent intent;

  /** Sobre todo para PROD: si quien lo pidió tenía permiso. */
  @Column(name = "authorized")
  private Boolean authorized;

  @Column(name = "outcome")
  private String outcome;

  @Column(name = "received_at", nullable = false)
  private Instant receivedAt = Instant.now();

  @Column(name = "handled_at")
  private Instant handledAt;

  public CommandExecution(final String commentId, final String issueKey) {
    this.commentId = commentId;
    this.issueKey = issueKey;
  }
}

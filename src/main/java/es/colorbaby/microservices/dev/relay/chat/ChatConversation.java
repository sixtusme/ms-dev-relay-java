package es.colorbaby.microservices.dev.relay.chat;

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
 * Una conversación del panel para preguntar por lo que ha hecho sixai. Está separada de los
 * comandos de Jira a propósito: <b>aquí se consulta y se razona; allí se ordena y se actúa</b>.
 */
@Entity
@Table(name = "chat_conversation")
@Getter
@Setter
@NoArgsConstructor
public class ChatConversation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_run_id")
  private Long taskRunId;

  /** Tarea sobre la que se conversa, si la conversación va de una concreta. */
  @Column(name = "issue_key")
  private String issueKey;

  private String title;

  @Column(name = "created_by_account_id")
  private String createdByAccountId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  public ChatConversation(final String issueKey, final String title) {
    this.issueKey = issueKey;
    this.title = title;
  }
}

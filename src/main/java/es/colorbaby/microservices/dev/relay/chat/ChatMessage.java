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

/** Un mensaje de la conversación. */
@Entity
@Table(name = "chat_message")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage {

  /** Tope del contenido; debe coincidir con la columna. */
  public static final int CONTENT_MAX = 8000;

  public static final String USER = "user";
  public static final String ASSISTANT = "assistant";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "conversation_id", nullable = false)
  private Long conversationId;

  /** {@code user} o {@code assistant}. */
  @Column(nullable = false)
  private String role;

  @Column(nullable = false, length = CONTENT_MAX)
  private String content;

  /** Si la respuesta la generó el modelo, enlaza con su coste y latencia. */
  @Column(name = "llm_call_id")
  private Long llmCallId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();

  public ChatMessage(final Long conversationId, final String role, final String content) {
    this.conversationId = conversationId;
    this.role = role;
    this.content = content;
  }
}

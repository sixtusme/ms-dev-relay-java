package es.colorbaby.microservices.dev.relay.chat;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Mensajes de las conversaciones. */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

  /** Mensajes de una conversación en orden cronológico. */
  List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(Long conversationId);
}

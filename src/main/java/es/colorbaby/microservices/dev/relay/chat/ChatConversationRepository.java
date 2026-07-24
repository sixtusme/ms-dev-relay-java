package es.colorbaby.microservices.dev.relay.chat;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Conversaciones del panel. */
public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

  /** Conversaciones de una tarea, de más reciente a más antigua. */
  List<ChatConversation> findByIssueKeyOrderByCreatedAtDesc(String issueKey);

  /** La conversación viva de una tarea: se reutiliza en vez de abrir una por mensaje. */
  Optional<ChatConversation> findFirstByIssueKeyOrderByCreatedAtDesc(String issueKey);
}

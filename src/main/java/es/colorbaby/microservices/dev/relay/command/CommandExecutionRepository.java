package es.colorbaby.microservices.dev.relay.command;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Comandos recibidos: clave de idempotencia ({@code comment_id}) e historial. */
public interface CommandExecutionRepository extends JpaRepository<CommandExecution, Long> {

  /** Un comando por el comentario que lo originó. */
  Optional<CommandExecution> findByCommentId(String commentId);

  /** True si ese comentario ya se atendió. */
  boolean existsByCommentId(String commentId);
}

package es.colorbaby.microservices.dev.relay.ai.knowledge;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Documentos indexados del Knowledge. {@code findAll()} (heredado) trae todo el corpus, para
 * compararlo por coseno en memoria contra el embedding de una consulta. */
public interface IndexedDocumentRepository extends JpaRepository<IndexedDocument, Long> {

  Optional<IndexedDocument> findBySource(String source);
}

package es.colorbaby.microservices.dev.relay.ai.knowledge;

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
 * Un documento indexado del Knowledge: su contenido, el hash con el que se decide si hay que
 * reincrustarlo, y su vector de embedding (serializado como JSON). No confundir con
 * {@link es.colorbaby.microservices.dev.relay.ai.knowledge.record.KnowledgeDocument}, que es lo
 * que se devuelve como RESULTADO de una consulta (con su puntuación de relevancia); esta clase es
 * lo que se persiste.
 */
@Entity
@Table(name = "indexed_document")
@Getter
@Setter
@NoArgsConstructor
public class IndexedDocument {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Identifica el documento de forma estable (ej. {@code docs/arquitectura-paquetes.md}). */
  @Column(nullable = false, unique = true)
  private String source;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
  private String content;

  /** SHA-256 del contenido; si no cambia, no hace falta volver a pedir el embedding. */
  @Column(name = "content_hash", nullable = false)
  private String contentHash;

  @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
  private String embedding;

  @Column(name = "embedding_model", nullable = false)
  private String embeddingModel;

  @Column(name = "indexed_at", nullable = false)
  private Instant indexedAt = Instant.now();

  public IndexedDocument(final String source, final String title, final String content,
      final String contentHash, final String embedding, final String embeddingModel) {
    this.source = source;
    this.title = title;
    this.content = content;
    this.contentHash = contentHash;
    this.embedding = embedding;
    this.embeddingModel = embeddingModel;
  }
}

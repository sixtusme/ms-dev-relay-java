package es.colorbaby.microservices.dev.relay.report;

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
 * Índice de un fichero guardado en el FTP/SFTP. El fichero vive fuera, pero su ficha vive aquí: así
 * el panel lista y busca al instante y solo va a por el contenido cuando alguien lo abre.
 */
@Entity
@Table(name = "report")
@Getter
@Setter
@NoArgsConstructor
public class Report {

  /** Tipos de fichero que indexa sixai. */
  public static final String KIND_DELIVERY = "DELIVERY";
  public static final String KIND_ATTACHMENT = "ATTACHMENT";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_run_id")
  private Long taskRunId;

  @Column(name = "issue_key")
  private String issueKey;

  @Column(nullable = false)
  private String kind;

  @Column(nullable = false)
  private String title;

  /** Dónde está: {@code sftp://host/ruta/fichero.md}. */
  @Column(name = "storage_uri", nullable = false)
  private String storageUri;

  @Column(nullable = false)
  private String format = "md";

  @Column(name = "size_bytes")
  private Long sizeBytes;

  @Column(name = "generated_by")
  private String generatedBy;

  @Column(name = "generated_at", nullable = false)
  private Instant generatedAt = Instant.now();
}

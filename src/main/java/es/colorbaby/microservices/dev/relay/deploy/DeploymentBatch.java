package es.colorbaby.microservices.dev.relay.deploy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Lo que hay que desplegar de UNA tarea para UNA fase (PRE o PROD). La tarea solo avanza cuando
 * todos los despliegues del lote terminan bien; si uno falla, el lote se marca fallido y se avisa.
 *
 * <p>El lote se crea con los despliegues que <b>realmente</b> arrancaron, no con una lista
 * optimista: así un repo no desplegable no deja la tarea esperando para siempre.
 */
@Entity
@Table(name = "deployment_batch")
@Getter
@Setter
@NoArgsConstructor
public class DeploymentBatch {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "issue_key", nullable = false)
  private String issueKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DeploymentPhase phase;

  /** Informador de la tarea; en PRE se le reasigna al terminar. En PROD no se usa. */
  @Column(name = "reporter_account_id")
  private String reporterAccountId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DeploymentStatus status = DeploymentStatus.RUNNING;

  public DeploymentBatch(final String issueKey, final DeploymentPhase phase,
      final String reporterAccountId) {
    this.issueKey = issueKey;
    this.phase = phase;
    this.reporterAccountId = reporterAccountId;
  }
}

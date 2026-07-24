package es.colorbaby.microservices.dev.relay.verification;

import es.colorbaby.microservices.dev.relay.deploy.DeploymentStatus;
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
 * La comprobación de que una PR al menos <b>compila</b>, antes de que nadie la apruebe.
 *
 * <p>Es más corta que un despliegue —solo build, sin Harbor ni deploy— pero se persiste por el mismo
 * motivo: tarda minutos y un reinicio a mitad no puede dejar la PR sin veredicto para siempre.
 */
@Entity
@Table(name = "verification_run")
@Getter
@Setter
@NoArgsConstructor
public class VerificationRun {

  /** Longitud máxima del motivo de fallo; debe coincidir con la columna. */
  public static final int FAILURE_REASON_MAX = 2000;

  /** Puntos del recorrido. Sin fase de despliegue: aquí solo se compila. */
  public enum Stage {
    QUEUED, RUNNING
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "issue_key", nullable = false)
  private String issueKey;

  @Column(nullable = false)
  private String repo;

  /** Rama de la PR ({@code sixai/<ISSUE>-<ts>}), que es lo que se compila. */
  @Column(nullable = false)
  private String branch;

  @Column(name = "pr_number", nullable = false)
  private int prNumber;

  @Column(name = "build_job", nullable = false)
  private String buildJob;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Stage stage = Stage.QUEUED;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DeploymentStatus status = DeploymentStatus.RUNNING;

  @Column(name = "queue_url")
  private String queueUrl;

  @Column(name = "build_number", nullable = false)
  private int buildNumber;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "failure_reason", length = FAILURE_REASON_MAX)
  private String failureReason;

  public VerificationRun(final String issueKey, final String repo, final String branch,
      final int prNumber, final String buildJob) {
    this.issueKey = issueKey;
    this.repo = repo;
    this.branch = branch;
    this.prNumber = prNumber;
    this.buildJob = buildJob;
  }

  /** Suma un sondeo de la etapa actual y devuelve el total. */
  public int incrementAttempts() {
    return ++attempts;
  }

  /** Vuelve a empezar a contar: se llama al pasar de etapa, porque el tope es por etapa. */
  public void resetAttempts() {
    this.attempts = 0;
  }
}

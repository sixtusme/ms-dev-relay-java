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
 * El despliegue de un repo dentro de un lote, con su punto exacto del recorrido. Es la máquina de
 * estados, y está persistida a propósito: el recorrido dura ~30 minutos (build + deploy), así que
 * un reinicio a mitad no puede hacer que sixai pierda el hilo y deje la tarea callada.
 */
@Entity
@Table(name = "deployment_run")
@Getter
@Setter
@NoArgsConstructor
public class DeploymentRun {

  /** Longitud máxima del motivo de fallo; debe coincidir con la columna. */
  public static final int FAILURE_REASON_MAX = 2000;

  /** Puntos del recorrido. */
  public enum Stage {
    BUILD_QUEUED, BUILD_RUNNING, DEPLOY_QUEUED, DEPLOY_RUNNING
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "batch_id", nullable = false)
  private Long batchId;

  @Column(name = "issue_key", nullable = false)
  private String issueKey;

  /** Repo de GitHub que se compila. */
  @Column(nullable = false)
  private String repo;

  /** Nombre del servicio en Harbor/Ansible. */
  @Column(nullable = false)
  private String service;

  /** Rama que se compila: {@code develop} para PRE, {@code main}/{@code master} para PROD. */
  @Column(nullable = false)
  private String branch;

  /** Entorno destino (PRE, PROD, DINAHOSTING). */
  @Column(nullable = false)
  private String environment;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DeploymentPhase phase;

  @Column(name = "build_job", nullable = false)
  private String buildJob;

  @Column(name = "deploy_job", nullable = false)
  private String deployJob;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Stage stage = Stage.BUILD_QUEUED;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DeploymentStatus status = DeploymentStatus.RUNNING;

  /** Item en cola que se está resolviendo (del build o del deploy, según la fase). */
  @Column(name = "queue_url")
  private String queueUrl;

  @Column(name = "build_number", nullable = false)
  private int buildNumber;

  /** Etiqueta de Harbor que produjo el build; es lo que se despliega. */
  @Column(name = "version")
  private String imageVersion;

  @Column(name = "deploy_build_number", nullable = false)
  private int deployBuildNumber;

  @Column(nullable = false)
  private int attempts;

  /** Motivo del fallo (incluye el diagnóstico del LLM). Se trunca a la longitud de la columna. */
  @Column(name = "failure_reason", length = FAILURE_REASON_MAX)
  private String failureReason;

  public DeploymentRun(final Long batchId, final String issueKey, final String repo,
      final String service, final String branch, final String environment,
      final DeploymentPhase phase, final String buildJob, final String deployJob) {
    this.batchId = batchId;
    this.issueKey = issueKey;
    this.repo = repo;
    this.service = service;
    this.branch = branch;
    this.environment = environment;
    this.phase = phase;
    this.buildJob = buildJob;
    this.deployJob = deployJob;
  }

  /** Suma un sondeo y devuelve el total. */
  public int incrementAttempts() {
    return ++attempts;
  }
}

package es.colorbaby.microservices.dev.relay.deploy;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Despliegues persistidos. El orquestador retoma los {@code RUNNING} tras un reinicio. */
public interface DeploymentRunRepository extends JpaRepository<DeploymentRun, Long> {

  /** Despliegues aún en curso: los que hay que seguir avanzando en cada barrido. */
  List<DeploymentRun> findByStatus(DeploymentStatus status);

  /** Todos los despliegues de un lote, para decidir si el lote ya terminó. */
  List<DeploymentRun> findByBatchId(Long batchId);

  /** Despliegues de una tarea en el orden en que se lanzaron, para el panel de seguimiento. */
  List<DeploymentRun> findByIssueKeyOrderByIdAsc(String issueKey);
}

package es.colorbaby.microservices.dev.relay.deploy;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Lotes de despliegue persistidos. */
public interface DeploymentBatchRepository extends JpaRepository<DeploymentBatch, Long> {

  /**
   * Lotes de una tarea en un estado dado. Sirve para no arrancar un despliegue encima de otro que
   * ya está en marcha (un doble clic en "Aprobar" lanzaría dos builds del mismo repo).
   */
  List<DeploymentBatch> findByIssueKeyAndStatus(String issueKey, DeploymentStatus status);
}

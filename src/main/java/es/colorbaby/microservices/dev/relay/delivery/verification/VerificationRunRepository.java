package es.colorbaby.microservices.dev.relay.delivery.verification;

import es.colorbaby.microservices.dev.relay.deploy.DeploymentStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Verificaciones de PR persistidas. El orquestador retoma las {@code RUNNING} tras un reinicio. */
public interface VerificationRunRepository extends JpaRepository<VerificationRun, Long> {

  /** Verificaciones aún en curso: las que hay que seguir avanzando en cada barrido. */
  List<VerificationRun> findByStatus(DeploymentStatus status);

  /** Verificaciones de una tarea, de la más reciente a la más antigua. */
  List<VerificationRun> findByIssueKeyOrderByIdDesc(String issueKey);
}

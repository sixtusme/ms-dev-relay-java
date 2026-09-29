package es.colorbaby.microservices.dev.relay.ai.orchestration;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Ejecuciones de agente paradas en {@code NEEDS_INPUT}. */
public interface AgentSuspensionRepository extends JpaRepository<AgentSuspension, Long> {

  Optional<AgentSuspension> findByIssueKeyAndRepoAndAgentId(
      String issueKey, String repo, String agentId);

  /** Si una tarea tiene alguna ejecución esperando respuesta, sea de qué repo o agente sea. */
  Optional<AgentSuspension> findFirstByIssueKeyOrderByIdDesc(String issueKey);

  void deleteByIssueKeyAndRepoAndAgentId(String issueKey, String repo, String agentId);
}

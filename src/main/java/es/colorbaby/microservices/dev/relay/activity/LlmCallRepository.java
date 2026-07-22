package es.colorbaby.microservices.dev.relay.activity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Llamadas al modelo, para coste y latencia. */
public interface LlmCallRepository extends JpaRepository<LlmCall, Long> {

  /** Llamadas de una issue, para calcular su coste y latencia. */
  List<LlmCall> findByIssueKey(String issueKey);
}

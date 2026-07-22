package es.colorbaby.microservices.dev.relay.activity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Línea de tiempo de las tareas. */
public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {

  /** Eventos de una issue en orden cronológico. */
  List<TaskEvent> findByIssueKeyOrderByOccurredAtAsc(String issueKey);
}

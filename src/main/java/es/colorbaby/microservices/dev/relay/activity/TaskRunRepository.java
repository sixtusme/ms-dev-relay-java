package es.colorbaby.microservices.dev.relay.activity;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tareas cogidas por sixai. */
public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {

  /** Tarea viva de una issue (la más reciente sin terminar), si la hay. */
  Optional<TaskRun> findFirstByIssueKeyAndStatusOrderByStartedAtDesc(String issueKey, String status);

  /** Historial de una issue, de más reciente a más antigua. */
  List<TaskRun> findByIssueKeyOrderByStartedAtDesc(String issueKey);
}

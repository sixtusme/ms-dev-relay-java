package es.colorbaby.microservices.dev.relay.control.lifecycle;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Pasadas de las tareas por sus fases. */
public interface TaskPhaseRunRepository extends JpaRepository<TaskPhaseRun, Long> {

  /** Todas las pasadas de una tarea, en el orden en que se abrieron: la última es la actual. */
  List<TaskPhaseRun> findByTaskRunIdOrderByIdAsc(Long taskRunId);
}

package es.colorbaby.microservices.dev.relay.activity;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tareas cogidas por sixai. */
public interface TaskRunRepository extends JpaRepository<TaskRun, Long> {

  /** Tarea viva de una issue (la más reciente sin terminar), si la hay. */
  Optional<TaskRun> findFirstByIssueKeyAndStatusOrderByStartedAtDesc(String issueKey, String status);

  /**
   * Igual que {@link #findFirstByIssueKeyAndStatusOrderByStartedAtDesc}, pero bloqueando la fila
   * ({@code SELECT ... FOR UPDATE}) hasta que termine la transacción que la pide.
   *
   * <p>Sin esto, dos hilos que tocan la misma tarea a la vez — el executor async que abre PRs y el
   * sweeper de verificación/despliegue son dos pools distintos, así que sí pueden solaparse de
   * verdad — pueden leer el mismo {@code TaskRun} antes de que el otro escriba, y el segundo en
   * escribir pisa una foto ya obsoleta (menor pendiente `§7.5`; es también la idempotencia que pide
   * mejoras-senior ítem 4 antes de activar {@code enforce=true}: sin serializar, un
   * {@code accept()} que hoy solo avisaría en modo estricto podría rechazar algo que en realidad ya
   * estaba resuelto por el otro hilo).
   * {@code es.colorbaby.microservices.dev.relay.control.lifecycle.TaskLifecycle} es el único que
   * la usa: es quien hace el ciclo leer-decidir-escribir sobre la fila.
   *
   * <p>Una lectura normal (sin {@code FOR UPDATE}, como {@code TaskLifecycle.snapshot}) nunca
   * espera por este lock — solo bloquea a otro escritor, no a los lectores del panel/STATUS.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from TaskRun t where t.issueKey = :issueKey and t.status = :status "
      + "order by t.startedAt desc")
  List<TaskRun> lockLiveTasks(@Param("issueKey") String issueKey, @Param("status") String status);

  /** Historial de una issue, de más reciente a más antigua. */
  List<TaskRun> findByIssueKeyOrderByStartedAtDesc(String issueKey);

  /** Tareas en un estado dado, de más reciente a más antigua: lo que pinta el panel de en curso. */
  List<TaskRun> findByStatusOrderByStartedAtDesc(String status);
}

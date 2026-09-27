package es.colorbaby.microservices.dev.relay.panel.metrics;

import es.colorbaby.microservices.dev.relay.activity.TaskEventRepository;
import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRun;
import es.colorbaby.microservices.dev.relay.activity.TaskRunRepository;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceKind;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceSource;
import es.colorbaby.microservices.dev.relay.control.lifecycle.FailureReason;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseEvidence;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseEvidenceRepository;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Recommendation;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskPhase;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskPhaseRun;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskPhaseRunRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calcula las métricas del ciclo de vida (mejoras-senior §14) a partir de lo ya persistido, sin
 * añadir una librería de métricas nueva: los números que hacen falta ya están en
 * {@code task_run}/{@code task_phase}/{@code task_phase_evidence}/{@code task_event}, y esto es
 * poco frecuente (una consulta puntual, no un contador que se actualiza en caliente).
 *
 * <p>Global, no por tarea: a diferencia de {@code TaskMonitorService}/{@code LifecycleSnapshot},
 * que hablan de UNA tarea, esto agrega TODO lo que sixai ha hecho hasta ahora.
 */
@Component
@RequiredArgsConstructor
public class LifecycleMetricsService {

  private final TaskRunRepository tasks;
  private final TaskPhaseRunRepository phases;
  private final PhaseEvidenceRepository evidences;
  private final TaskEventRepository events;

  @Transactional(readOnly = true)
  public LifecycleMetrics compute() {
    final List<TaskRun> allTasks = tasks.findAll();
    final long started = allTasks.size();
    final long completed = allTasks.stream().filter(t -> TaskRun.DONE.equals(t.getStatus())).count();
    final long failed = allTasks.stream().filter(t -> TaskRun.FAILED.equals(t.getStatus())).count();

    final List<TaskPhaseRun> allPhases = phases.findAll();
    final long escalated = allPhases.stream()
        .filter(p -> p.getPhase() == TaskPhase.ESCALATED)
        .map(TaskPhaseRun::getTaskRunId)
        .distinct()
        .count();
    // Cada fila de IMPLEMENTATION con iteración > 0 es una corrección (manual o automática) que
    // llegó a reroute(); es la misma cuenta que decide el presupuesto de R3.
    final long corrections = allPhases.stream()
        .filter(p -> p.getPhase() == TaskPhase.IMPLEMENTATION && p.getIteration() > 0)
        .count();
    final Map<TaskPhase, Double> avgDuration = allPhases.stream()
        .filter(p -> p.getFinishedAt() != null)
        .collect(Collectors.groupingBy(TaskPhaseRun::getPhase,
            Collectors.averagingLong(
                p -> Duration.between(p.getStartedAt(), p.getFinishedAt()).toMillis())));

    final long violations = events.findAll().stream()
        .filter(e -> e.getType() == TaskEventType.LIFECYCLE_VIOLATION)
        .count();

    final List<PhaseEvidence> allEvidence = evidences.findAll();
    // Un agente pide aclaración: evidencia BLOCKED cuyo origen es un agente (ver PullRequestService
    // / CoderAgent, Fase C). No se cuenta desde agent_suspension porque esa tabla se vacía al
    // reanudar: la evidencia, en cambio, es append-only y queda para siempre.
    final List<PhaseEvidence> agentBlocks = allEvidence.stream()
        .filter(e -> e.getRecommendation() == Recommendation.BLOCKED
            && e.getSource() == EvidenceSource.AGENT)
        .toList();
    final long agentQuestions = agentBlocks.size();
    // Tasa de bloqueos (mejoras-senior §14, ítem no calculado hasta ahora): a diferencia de
    // agentQuestions (cuenta preguntas), esto cuenta TAREAS — una que preguntó tres veces solo
    // cuenta una. Hace falta ir de evidencia → fase → tarea porque la evidencia no lleva el
    // taskRunId directamente, solo el id de su fila de fase.
    final Map<Long, Long> taskRunIdByPhaseId = allPhases.stream()
        .collect(Collectors.toMap(TaskPhaseRun::getId, TaskPhaseRun::getTaskRunId));
    final long tasksBlocked = agentBlocks.stream()
        .map(e -> taskRunIdByPhaseId.get(e.getTaskPhaseId()))
        .filter(Objects::nonNull)
        .distinct()
        .count();
    final long agentErrors = allEvidence.stream()
        .filter(e -> e.getReasonCode() == FailureReason.AGENT_ERROR)
        .count();
    final long decisions = allEvidence.stream()
        .filter(e -> e.getKind() == EvidenceKind.DECISION)
        .count();

    return new LifecycleMetrics(started, completed, failed, escalated, tasksBlocked, corrections,
        violations, agentQuestions, agentErrors, decisions, avgDuration);
  }
}

package es.colorbaby.microservices.dev.relay.panel.metrics;

import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskPhase;
import java.util.Map;

/**
 * Foto de las métricas del ciclo de vida (mejoras-senior §14.1), calculada sobre lo ya persistido
 * en {@code task_run}/{@code task_phase}/{@code task_phase_evidence}/{@code task_event} — sin
 * infraestructura de métricas nueva (no hay Micrometer/Prometheus en este servicio).
 *
 * @param avgPhaseDurationMs cuánto tarda de media una fase cerrada, en milisegundos, por fase
 * @param tasksBlocked       tareas que necesitaron al menos una aclaración de un agente (distinto
 *                           de {@code agentQuestions}, que cuenta preguntas, no tareas: una tarea
 *                           puede preguntar varias veces y aquí solo cuenta una)
 */
public record LifecycleMetrics(
    long tasksStarted,
    long tasksCompleted,
    long tasksFailed,
    long tasksEscalated,
    long tasksBlocked,
    long correctionCount,
    long lifecycleViolations,
    long agentQuestions,
    long agentErrors,
    long humanDecisions,
    Map<TaskPhase, Double> avgPhaseDurationMs) {
}

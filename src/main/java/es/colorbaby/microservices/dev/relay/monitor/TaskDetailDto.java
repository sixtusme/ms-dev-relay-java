package es.colorbaby.microservices.dev.relay.monitor;

import java.util.List;

/**
 * El detalle de una tarea: quién la pidió, en qué punto está y todo lo que ha ido pasando. Es lo
 * que se ve al abrir una tarea desde el panel para seguir su avance.
 */
public record TaskDetailDto(String issueKey, String title, String epic, String status,
    String stage, String stageKey, String requestedBy, String startedAt, Long durationMs,
    List<TaskEventDto> events, List<TaskDeploymentDto> deployments) {

  /** Un hito de la línea de tiempo. */
  public record TaskEventDto(String type, String actor, String detail, String occurredAt) {
  }

  /** Un despliegue de la tarea, con el punto exacto de su recorrido. */
  public record TaskDeploymentDto(String repo, String environment, String phase, String stage,
      String status, String version) {
  }
}

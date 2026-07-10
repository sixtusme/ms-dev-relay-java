package es.colorbaby.microservices.dev.relay.event;

import java.time.Instant;

/**
 * Publicado cuando una tarea de Jira resulta elegible para ser procesada.
 * El módulo de orquestación futuro (Claude Code/Git/Jenkins) se engancha a
 * este evento sin que este servicio necesite conocerlo.
 */
public record IssueEligibleEvent(
    String issueKey,
    String assigneeAccountId,
    String triggeringCommentId,
    TriggerSource source,
    Instant detectedAt) {
}

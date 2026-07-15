package es.colorbaby.microservices.dev.relay.event;

import java.time.Instant;

/**
 * Publicado cuando una tarea de Jira resulta elegible para ser procesada.
 * El módulo de orquestación futuro (Claude Code/Git/Jenkins) se engancha a
 * este evento sin que este servicio necesite conocerlo.
 *
 * <p>Lleva los datos del comentario que disparó el trigger (autor y texto) para que el responder
 * pueda mencionar al autor y citar su comentario sin volver a ir a Jira.
 */
public record IssueEligibleEvent(
    String issueKey,
    String assigneeAccountId,
    String triggeringCommentId,
    String triggeringCommentAuthorAccountId,
    String triggeringCommentAuthorName,
    String triggeringCommentText,
    TriggerSource source,
    Instant detectedAt) {
}

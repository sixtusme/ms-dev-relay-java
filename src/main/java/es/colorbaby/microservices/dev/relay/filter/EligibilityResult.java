package es.colorbaby.microservices.dev.relay.filter;

import es.colorbaby.microservices.dev.relay.openapi.model.JiraCommentDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;

/**
 * Resultado positivo del filtro de elegibilidad: la issue y el comentario
 * que ha disparado el procesamiento.
 */
public record EligibilityResult(JiraIssueDto issue, JiraCommentDto triggeringComment) {
}

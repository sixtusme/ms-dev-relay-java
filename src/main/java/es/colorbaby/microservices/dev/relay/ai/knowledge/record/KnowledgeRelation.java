package es.colorbaby.microservices.dev.relay.ai.knowledge.record;

/**
 * Una relación del grafo de conocimiento entre dos entidades (ej. "servicio X depende de Y").
 * Reservado: hoy {@link es.colorbaby.microservices.dev.relay.ai.knowledge.impl.DocumentKnowledgeService}
 * no lo rellena (retrieval semántico sobre documentos, sin grafo todavía).
 */
public record KnowledgeRelation(
        String sourceId,
        String relation,
        String targetId
) {
}

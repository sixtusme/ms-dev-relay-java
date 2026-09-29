package es.colorbaby.microservices.dev.relay.ai.knowledge.record;

/** Un documento (o fragmento) recuperado del Knowledge, con su puntuación de relevancia. */
public record KnowledgeDocument(
        String id,
        String title,
        String content,
        String source,
        double score
) {
}

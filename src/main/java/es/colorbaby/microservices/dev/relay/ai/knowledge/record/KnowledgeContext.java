package es.colorbaby.microservices.dev.relay.ai.knowledge.record;

import java.util.List;

public record KnowledgeContext (
    List<KnowledgeDocument> documents,
    List<KnowledgeRelation> relations
) {

}

record KnowledgeDocument(
        String id,
        String title,
        String content,
        String source,
        double score
) { }

record KnowledgeRelation(
        String sourceId,
        String relation,
        String targetId
) { }

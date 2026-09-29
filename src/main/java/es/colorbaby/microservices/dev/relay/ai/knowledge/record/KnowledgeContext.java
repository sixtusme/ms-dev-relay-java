package es.colorbaby.microservices.dev.relay.ai.knowledge.record;

import java.util.List;

public record KnowledgeContext (
    List<KnowledgeDocument> documents,
    List<KnowledgeRelation> relations
) {

}

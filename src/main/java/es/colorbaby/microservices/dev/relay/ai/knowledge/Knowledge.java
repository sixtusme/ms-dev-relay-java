package es.colorbaby.microservices.dev.relay.ai.knowledge;

import es.colorbaby.microservices.dev.relay.ai.knowledge.record.KnowledgeContext;
import es.colorbaby.microservices.dev.relay.ai.knowledge.record.KnowledgeQuery;

public interface Knowledge {
    KnowledgeContext retrieve(KnowledgeQuery query);
}

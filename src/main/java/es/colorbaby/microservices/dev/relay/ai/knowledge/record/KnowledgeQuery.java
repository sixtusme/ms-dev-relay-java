package es.colorbaby.microservices.dev.relay.ai.knowledge.record;

public record KnowledgeQuery(
        String query,
        String issueKey,
        int maxResults
) {
}

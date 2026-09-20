package es.colorbaby.microservices.dev.relay.ai.knowledge.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.colorbaby.microservices.dev.relay.ai.knowledge.IndexedDocument;
import es.colorbaby.microservices.dev.relay.ai.knowledge.IndexedDocumentRepository;
import es.colorbaby.microservices.dev.relay.ai.knowledge.Knowledge;
import es.colorbaby.microservices.dev.relay.ai.knowledge.record.KnowledgeContext;
import es.colorbaby.microservices.dev.relay.ai.knowledge.record.KnowledgeDocument;
import es.colorbaby.microservices.dev.relay.ai.knowledge.record.KnowledgeQuery;
import es.colorbaby.microservices.dev.relay.config.KnowledgeProperties;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Implementación del Knowledge por retrieval semántico: embebe la consulta, compara por coseno
 * contra el embedding de cada {@link IndexedDocument} del corpus (la documentación del proyecto,
 * indexada por {@link es.colorbaby.microservices.dev.relay.ai.knowledge.DocumentKnowledgeIndexer})
 * y devuelve los más relevantes.
 *
 * <p>Sin vector DB dedicado: el corpus es pequeño (documentación, no todo el código ni el
 * histórico de Jira), así que comparar en memoria en cada consulta es barato y no añade
 * infraestructura nueva. Si el corpus crece mucho, aquí es donde habría que introducir un índice
 * de verdad — pero no antes de necesitarlo.
 *
 * <p>{@code relations} siempre vacío: el grafo de conocimiento es la segunda fase de la hoja de
 * ruta, no esta.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentKnowledgeService implements Knowledge {

  private final KnowledgeProperties properties;
  private final LlmProperties llmProperties;
  private final LlmClient llmClient;
  private final IndexedDocumentRepository documents;
  private final ObjectMapper objectMapper;

  @Override
  public KnowledgeContext retrieve(final KnowledgeQuery query) {
    if (!properties.isEnabled() || !llmProperties.isEnabled()
        || query.query() == null || query.query().isBlank()) {
      return empty();
    }
    try {
      return doRetrieve(query);
    } catch (RuntimeException e) {
      log.warn("No se pudo consultar el Knowledge: {}", e.getMessage());
      return empty();
    }
  }

  private KnowledgeContext doRetrieve(final KnowledgeQuery query) {
    final List<IndexedDocument> corpus = documents.findAll();
    if (corpus.isEmpty()) {
      return empty();
    }
    final double[] queryVector = toArray(llmClient.embed(query.query(), query.issueKey()));
    final int max = query.maxResults() > 0 ? query.maxResults() : properties.getMaxResults();

    final List<KnowledgeDocument> results = corpus.stream()
        .map(document -> score(document, queryVector))
        .filter(document -> document.score() >= properties.getMinScore())
        .sorted(Comparator.comparingDouble(KnowledgeDocument::score).reversed())
        .limit(max)
        .toList();
    return new KnowledgeContext(results, List.of());
  }

  private KnowledgeDocument score(final IndexedDocument document, final double[] queryVector) {
    final double similarity = cosineSimilarity(queryVector, toArray(readEmbedding(document)));
    return new KnowledgeDocument(String.valueOf(document.getId()), document.getTitle(),
        document.getContent(), document.getSource(), similarity);
  }

  private List<Double> readEmbedding(final IndexedDocument document) {
    try {
      return objectMapper.readValue(document.getEmbedding(), new TypeReference<List<Double>>() { });
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Embedding ilegible para " + document.getSource(), e);
    }
  }

  private double[] toArray(final List<Double> values) {
    return values.stream().mapToDouble(Double::doubleValue).toArray();
  }

  private double cosineSimilarity(final double[] a, final double[] b) {
    if (a.length != b.length || a.length == 0) {
      return 0.0;
    }
    double dot = 0;
    double normA = 0;
    double normB = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      normA += a[i] * a[i];
      normB += b[i] * b[i];
    }
    if (normA == 0 || normB == 0) {
      return 0.0;
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  private KnowledgeContext empty() {
    return new KnowledgeContext(List.of(), List.of());
  }
}

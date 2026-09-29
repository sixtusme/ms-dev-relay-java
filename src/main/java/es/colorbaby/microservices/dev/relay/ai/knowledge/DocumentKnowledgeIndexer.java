package es.colorbaby.microservices.dev.relay.ai.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import es.colorbaby.microservices.dev.relay.config.KnowledgeProperties;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.ai.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.ai.llm.LlmRoles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Indexa la documentación del proyecto para el Knowledge, al arrancar la aplicación.
 *
 * <p>Primera fase de "documentos → chunks → retrieval semántico": por ahora cada fichero es UN
 * documento (sin trocear) — la documentación de arquitectura cabe de sobra en el límite de entrada
 * de un modelo de embeddings, y trocear sin necesidad sería complejidad sin beneficio. Si el corpus
 * crece hasta necesitarlo, se trocea aquí sin que nadie más se entere.
 *
 * <p>Solo reincrusta (llama al LLM) un documento si su contenido cambió desde la última vez: el
 * hash SHA-256 decide.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentKnowledgeIndexer implements ApplicationRunner {

  private final KnowledgeProperties properties;
  private final LlmProperties llmProperties;
  private final LlmClient llmClient;
  private final IndexedDocumentRepository documents;
  private final ObjectMapper objectMapper;

  @Override
  public void run(final ApplicationArguments args) {
    if (!properties.isEnabled() || !llmProperties.isEnabled()) {
      return;
    }
    try {
      indexAll();
    } catch (IOException | RuntimeException e) {
      log.warn("No se pudo indexar el Knowledge: {}", e.getMessage());
    }
  }

  private void indexAll() throws IOException {
    final Resource[] resources =
        new PathMatchingResourcePatternResolver().getResources(properties.getDocumentsLocation());
    int indexed = 0;
    for (final Resource resource : resources) {
      try {
        if (indexOne(resource)) {
          indexed++;
        }
      } catch (RuntimeException e) {
        log.warn("No se pudo indexar {}: {}", resource.getFilename(), e.getMessage());
      }
    }
    log.info("Knowledge: {} documento(s) revisado(s) desde {}, {} reincrustado(s)",
        resources.length, properties.getDocumentsLocation(), indexed);
  }

  /** @return true si se (re)incrustó el documento; false si no había cambios. */
  private boolean indexOne(final Resource resource) throws IOException {
    final String content = resource.getContentAsString(StandardCharsets.UTF_8);
    final String source = resource.getFilename();
    final String hash = sha256(content);

    final Optional<IndexedDocument> existing = documents.findBySource(source);
    if (existing.isPresent() && hash.equals(existing.get().getContentHash())) {
      return false;
    }

    final List<Double> embedding = llmClient.embed(content);
    final String embeddingJson = writeJson(embedding);
    final String title = titleOf(content, source);
    final String model = llmProperties.modelFor(LlmRoles.EMBEDDING);

    final IndexedDocument document = existing.orElseGet(() ->
        new IndexedDocument(source, title, content, hash, embeddingJson, model));
    document.setTitle(title);
    document.setContent(content);
    document.setContentHash(hash);
    document.setEmbedding(embeddingJson);
    document.setEmbeddingModel(model);
    document.setIndexedAt(Instant.now());
    documents.save(document);
    log.info("Knowledge: indexado {}", source);
    return true;
  }

  /** El título es el primer encabezado Markdown ("# ...") o, si no hay, el nombre del fichero. */
  private String titleOf(final String content, final String source) {
    return content.lines()
        .map(String::strip)
        .filter(line -> line.startsWith("# "))
        .findFirst()
        .map(line -> line.substring(2).strip())
        .orElse(source);
  }

  private String writeJson(final List<Double> embedding) {
    try {
      return objectMapper.writeValueAsString(embedding);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("No se pudo serializar el embedding", e);
    }
  }

  private String sha256(final String content) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 no disponible", e);
    }
  }
}

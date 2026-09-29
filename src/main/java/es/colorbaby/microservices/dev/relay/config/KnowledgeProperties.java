package es.colorbaby.microservices.dev.relay.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Knowledge de sixai ({@code maestro.knowledge}): retrieval semántico sobre la documentación del
 * proyecto (hoy, {@code resources/docs/*.md}). Requiere además {@code maestro.llm.enabled=true},
 * porque indexar y consultar necesitan embeddings.
 *
 * <p>Primera fase de la hoja de ruta de la arquitectura: "documentos → chunks → retrieval
 * semántico". El grafo de conocimiento (relaciones entre entidades) queda para más adelante — no
 * hay infraestructura para él todavía, y no hace falta mientras nadie la consuma.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "maestro.knowledge")
public class KnowledgeProperties {

  /** Interruptor general. Con {@code false}, {@code Knowledge.retrieve} devuelve siempre vacío. */
  private boolean enabled = false;

  /** Patrón classpath de los documentos a indexar. */
  private String documentsLocation = "classpath:docs/*.md";

  /** Máximo de documentos que devuelve una consulta. */
  @Positive
  private int maxResults = 5;

  /**
   * Puntuación mínima (similitud coseno, 0..1) para que un documento cuente como relevante. Evita
   * devolver "lo menos malo" cuando en realidad nada del corpus responde a la pregunta.
   */
  @DecimalMin("0.0")
  @DecimalMax("1.0")
  private double minScore = 0.5;
}

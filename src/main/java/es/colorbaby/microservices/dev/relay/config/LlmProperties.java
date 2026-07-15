package es.colorbaby.microservices.dev.relay.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del LLM ({@code maestro.llm} de application.yml).
 *
 * <p>Permite cambiar de IA y de modelo sin tocar código. Los valores por defecto apuntan a un
 * Ollama local; para un proveedor cloud basta con rellenar {@code base-url}, {@code model} y
 * {@code api-key}.
 */
@ConfigurationProperties(prefix = "maestro.llm")
@Getter
@Setter
public class LlmProperties {

  /**
   * Interruptor de la IA. Con {@code false}, el responder solo comenta y pone la tarea en curso,
   * sin llamar a ningún modelo. Se pone en {@code true} cuando hay un LLM disponible.
   */
  private boolean enabled = false;

  /** Familia de API. De momento solo {@code openai-compatible} (Ollama, OpenAI, LM Studio…). */
  private String provider = "openai-compatible";

  /** URL base del endpoint compatible con OpenAI, sin la barra final. */
  private String baseUrl = "http://localhost:11434/v1";

  /** Modelo a usar (p. ej. {@code llama3.2}, {@code gpt-4o-mini}). */
  private String model = "llama3.2";

  /** Clave del proveedor. Vacía para Ollama; el token para un proveedor cloud. */
  private String apiKey = "";

  /** Creatividad de la respuesta (0 = determinista). */
  private double temperature = 0.4;

  /** Tope de tokens de la respuesta. */
  private int maxTokens = 1024;

  /** Timeout de conexión. */
  private int connectTimeoutMs = 5000;

  /** Timeout de lectura. Amplio: los modelos locales tardan. */
  private int readTimeoutMs = 120000;

  /** System prompt que define el comportamiento de Sixai. */
  private String systemPrompt = "";
}

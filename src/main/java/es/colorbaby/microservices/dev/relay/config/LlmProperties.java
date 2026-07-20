package es.colorbaby.microservices.dev.relay.config;

import java.util.HashMap;
import java.util.Map;
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

  /** Modelo por defecto y de reserva (p. ej. {@code llama3.2}, {@code gpt-4o-mini}). */
  private String model = "llama3.2";

  /**
   * Alias de modelo por rol lógico ({@code responder}, {@code selector}, {@code planner}…). Con un
   * gateway de modelos delante, cada rol se enruta a un modelo distinto sin tocar código. Si un rol
   * no está aquí (o su alias está en blanco), se usa {@code model}. Ver /ai/gateway-de-modelos.
   */
  private Map<String, String> models = new HashMap<>();

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

  /**
   * Modelo a usar para un rol: su alias de {@code models} si está mapeado y no vacío; si no, el
   * {@code model} por defecto. Un alias en blanco (típico cuando la env-var no se define) equivale
   * a "usa el modelo por defecto", así que sembrar los roles vacíos en el yml es seguro.
   *
   * @param role rol lógico (ver {@code LlmRoles}); puede ser null o vacío
   * @return el alias del rol o, en su defecto, el modelo por defecto
   */
  public String modelFor(final String role) {
    if (role != null && !role.isBlank()) {
      final String alias = models.get(role);
      if (alias != null && !alias.isBlank()) {
        return alias;
      }
    }
    return model;
  }
}

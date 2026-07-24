package es.colorbaby.microservices.dev.relay.config;

import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
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

  /** Tope de tokens de la respuesta, para los roles que no tengan el suyo. */
  private int maxTokens = 1024;

  /**
   * Tope de tokens por rol. Igual que con los timeouts, no todos los roles producen lo mismo: el
   * {@code router} devuelve una palabra y el {@code coder} devuelve ficheros ENTEROS dentro de un
   * JSON.
   *
   * <p>Y quedarse corto aquí no es que la respuesta salga más pobre: sale <b>cortada a media
   * cadena</b>, con lo que el JSON del coder deja de ser parseable y sus cambios se pierden como si
   * el modelo no hubiera propuesto nada. Por eso el coder trae un valor por defecto propio en vez de
   * heredar {@code max-tokens}: es un techo que no se puede dejar al descuido de la configuración.
   */
  private Map<String, Integer> maxTokensByRole = new HashMap<>(Map.of(LlmRoles.CODER, 16000));

  /** Timeout de conexión. */
  private int connectTimeoutMs = 5000;

  /** Timeout de lectura por defecto. Amplio: los modelos locales tardan. */
  private int readTimeoutMs = 120000;

  /**
   * Timeout de lectura por rol, en milisegundos. No todos los roles necesitan lo mismo: el
   * {@code coder} genera ficheros enteros y puede tardar minutos, mientras que el {@code router}
   * solo devuelve una palabra. Con un único timeout, o se queda corto para el coder (y sus cambios
   * se pierden como si no hubiera propuesto nada) o es absurdamente largo para el resto.
   *
   * <p>Un rol sin entrada aquí usa {@code read-timeout-ms}.
   */
  private Map<String, Integer> readTimeouts = new HashMap<>();

  /** System prompt que define el comportamiento de Sixai. */
  private String systemPrompt = "";

  /**
   * Porcentaje de fallos a partir del cual se cortan las llamadas al modelo. Con el cortocircuito
   * abierto, todo lo que usa el LLM degrada a su alternativa en vez de quedarse esperando.
   */
  private int circuitFailureRateThreshold = 50;

  /** Llamadas mínimas antes de que el cortocircuito pueda abrirse (evita abrir por un fallo suelto). */
  private int circuitMinimumCalls = 5;

  /** Segundos que el cortocircuito permanece abierto antes de volver a probar. */
  private int circuitOpenSeconds = 60;

  /**
   * Timeout de lectura para un rol: el suyo si está configurado, o el general.
   *
   * @param role rol lógico (ver {@code LlmRoles}); puede ser null
   * @return milisegundos de espera antes de dar la llamada por perdida
   */
  public int readTimeoutFor(final String role) {
    if (role != null && !role.isBlank()) {
      final Integer specific = readTimeouts.get(role);
      if (specific != null && specific > 0) {
        return specific;
      }
    }
    return readTimeoutMs;
  }

  /**
   * Tope de tokens para un rol: el suyo si está configurado, o el general.
   *
   * @param role rol lógico (ver {@code LlmRoles}); puede ser null
   * @return número máximo de tokens que se le permite generar
   */
  public int maxTokensFor(final String role) {
    if (role != null && !role.isBlank()) {
      final Integer specific = maxTokensByRole.get(role);
      if (specific != null && specific > 0) {
        return specific;
      }
    }
    return maxTokens;
  }

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

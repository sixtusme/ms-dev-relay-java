package es.colorbaby.microservices.dev.relay.llm;

import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Cliente contra el API de chat compatible con OpenAI ({@code POST /chat/completions}).
 *
 * <p>Es el formato que exponen Ollama, LM Studio, OpenAI y muchos otros, así que la misma clase
 * sirve para todos con solo cambiar {@code base-url}, {@code model} y {@code api-key} en el yml.
 *
 * <p>Se navega la respuesta como {@link Map} a propósito: cada proveedor añade campos distintos, y
 * un mapeo tipado estricto fallaría con "unknown property" en cuanto uno de ellos devuelva un extra.
 */
@Slf4j
public class OpenAiCompatibleLlmClient implements LlmClient {

  /**
   * Un RestTemplate por timeout distinto, creado la primera vez que hace falta. El timeout se fija
   * al construir el request factory, así que no puede cambiarse por llamada: por eso se cachean por
   * valor en vez de crear uno nuevo cada vez.
   */
  private final Map<Integer, RestTemplate> restTemplates = new ConcurrentHashMap<>();

  private final LlmProperties properties;

  public OpenAiCompatibleLlmClient(LlmProperties properties) {
    this.properties = properties;
  }

  private RestTemplate restTemplateFor(final String role) {
    return restTemplates.computeIfAbsent(properties.readTimeoutFor(role), readTimeout -> {
      final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
      factory.setConnectTimeout(properties.getConnectTimeoutMs());
      factory.setReadTimeout(readTimeout);
      return new RestTemplate(factory);
    });
  }

  @Override
  public String complete(final LlmRequest request) {
    String url = properties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
    String model = properties.modelFor(request.role());

    int maxTokens = properties.maxTokensFor(request.role());

    Map<String, Object> body = Map.of(
        "model", model,
        "temperature", properties.getTemperature(),
        "max_tokens", maxTokens,
        "stream", false,
        "messages", List.of(
            Map.of("role", "system", "content", request.systemPrompt()),
            Map.of("role", "user", "content", request.userPrompt())));

    HttpHeaders headers = buildHeaders(request);

    Map<String, Object> response;
    try {
      response = postForMap(restTemplateFor(request.role()), url, new HttpEntity<>(body, headers));
    } catch (RestClientException e) {
      throw new LlmClientException("Fallo llamando al LLM (" + properties.getProvider()
          + " @ " + url + ", modelo " + model + ")", e);
    }

    // El corte se comprueba ANTES de leer el contenido: si el modelo se quedó sin tokens nada más
    // empezar, "respuesta vacía" despistaría sobre la causa real, que es el techo.
    Map<String, Object> choice = firstChoice(response);
    checkNotTruncated(choice, request, model, maxTokens);
    return extractContent(choice);
  }

  /**
   * Aviso (o fallo) cuando el modelo se quedó sin tokens a mitad de frase.
   *
   * <p>Sin esto, una respuesta cortada es indistinguible de una respuesta completa: el JSON del coder
   * deja de parsear y el log acaba diciendo "no propuso cambios", que es justo lo contrario de lo que
   * pasó. Quien exige respuesta completa recibe un fallo; el resto, al menos, un aviso con el número
   * exacto que hay que subir.
   */
  private void checkNotTruncated(final Map<String, Object> choice, final LlmRequest request,
      final String model, final int maxTokens) {
    if (!"length".equals(finishReason(choice))) {
      return;
    }
    String role = request.role() == null ? "sin rol" : request.role();
    String message = "El modelo " + model + " agotó el tope de " + maxTokens
        + " tokens y su respuesta salió cortada (rol " + role
        + "). Sube maestro.llm.max-tokens-by-role." + role;
    if (request.requireComplete()) {
      throw new LlmTruncatedException(message);
    }
    log.warn("{}", message);
  }

  private String finishReason(final Map<String, Object> choice) {
    Object reason = choice.get("finish_reason");
    return reason == null ? null : reason.toString();
  }

  /**
   * Cabeceras de la petición: content-type, auth (si hay clave) y la metadata como {@code x-sixai-*}
   * (rol e issue). Ollama y OpenAI ignoran esas cabeceras; un gateway de modelos las usa para traza
   * y coste por tarea. El prefijo {@code x-sixai-} evita chocar con nada estándar.
   */
  private HttpHeaders buildHeaders(final LlmRequest request) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
      headers.setBearerAuth(properties.getApiKey());
    }
    if (request.role() != null && !request.role().isBlank()) {
      headers.add("x-sixai-role", request.role());
    }
    request.metadata().forEach((key, value) -> {
      if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
        headers.add("x-sixai-" + key, value);
      }
    });
    return headers;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> postForMap(final RestTemplate restTemplate, final String url,
      final HttpEntity<?> request) {
    return restTemplate.postForObject(url, request, Map.class);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> firstChoice(final Map<String, Object> response) {
    if (response == null) {
      throw new LlmClientException("El LLM no devolvió respuesta");
    }
    Object choices = response.get("choices");
    if (!(choices instanceof List<?> list) || list.isEmpty()) {
      throw new LlmClientException("Respuesta del LLM sin 'choices': " + response);
    }
    Object first = list.get(0);
    if (!(first instanceof Map<?, ?> choice)) {
      throw new LlmClientException("Formato de 'choices' inesperado: " + first);
    }
    return (Map<String, Object>) choice;
  }

  @SuppressWarnings("unchecked")
  private String extractContent(final Map<String, Object> choice) {
    Object message = choice.get("message");
    if (!(message instanceof Map<?, ?> msg)) {
      throw new LlmClientException("Respuesta del LLM sin 'message': " + choice);
    }
    Object content = ((Map<String, Object>) msg).get("content");
    if (content == null || content.toString().isBlank()) {
      throw new LlmClientException("Respuesta del LLM con contenido vacío");
    }
    return content.toString().trim();
  }
}
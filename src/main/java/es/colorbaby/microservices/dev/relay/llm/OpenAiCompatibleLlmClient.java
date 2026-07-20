package es.colorbaby.microservices.dev.relay.llm;

import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

  private final RestTemplate restTemplate;
  private final LlmProperties properties;

  public OpenAiCompatibleLlmClient(RestTemplate llmRestTemplate, LlmProperties properties) {
    this.restTemplate = llmRestTemplate;
    this.properties = properties;
  }

  @Override
  public String complete(final LlmRequest request) {
    String url = properties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
    String model = properties.modelFor(request.role());

    Map<String, Object> body = Map.of(
        "model", model,
        "temperature", properties.getTemperature(),
        "max_tokens", properties.getMaxTokens(),
        "stream", false,
        "messages", List.of(
            Map.of("role", "system", "content", request.systemPrompt()),
            Map.of("role", "user", "content", request.userPrompt())));

    HttpHeaders headers = buildHeaders(request);

    Map<String, Object> response;
    try {
      response = postForMap(url, new HttpEntity<>(body, headers));
    } catch (RestClientException e) {
      throw new LlmClientException("Fallo llamando al LLM (" + properties.getProvider()
          + " @ " + url + ", modelo " + model + ")", e);
    }

    return extractContent(response);
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
  private Map<String, Object> postForMap(final String url, final HttpEntity<?> request) {
    return restTemplate.postForObject(url, request, Map.class);
  }

  @SuppressWarnings("unchecked")
  private String extractContent(final Map<String, Object> response) {
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
    Object message = ((Map<String, Object>) choice).get("message");
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
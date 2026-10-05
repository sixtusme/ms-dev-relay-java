package es.colorbaby.microservices.dev.relay.ai.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Cliente contra el API de Claude, con el SDK oficial de Anthropic.
 *
 * <p>Los embeddings se delegan en otro cliente (el compatible con OpenAI, contra Ollama): Anthropic
 * no tiene API de embeddings, y el {@code Knowledge} los necesita.
 *
 * <p>Diferencias con el cliente compatible con OpenAI que conviene tener presentes:
 * <ul>
 *   <li>No se manda {@code temperature}: los modelos actuales de Claude la rechazan con un 400. El
 *       mando equivalente es el esfuerzo ({@code maestro.llm.anthropic.effort}).</li>
 *   <li>El razonamiento va siempre activo y sus tokens cuentan dentro de {@code max_tokens}. Por eso
 *       se aplica un suelo: con los 200 tokens del router, el razonamiento se los comería antes de
 *       contestar. Es un techo, no un gasto: solo se paga lo que se genera.</li>
 * </ul>
 */
@Slf4j
public class AnthropicLlmClient implements LlmClient {

  /** Suelo de {@code max_tokens}, para que el razonamiento no deje la respuesta cortada. */
  private static final int MIN_MAX_TOKENS = 16000;

  /**
   * Modelos que aceptan el reenvío automático a otro modelo si rechazan la petición
   * ({@code fallbacks: "default"}). Sin él, un rechazo deja la llamada sin respuesta.
   */
  private static final Set<String> FALLBACK_MODELS =
      Set.of("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");

  private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

  private final LlmProperties properties;
  private final LlmClient embeddings;
  private final AnthropicClient client;

  public AnthropicLlmClient(final LlmProperties properties, final LlmClient embeddings) {
    this.properties = properties;
    this.embeddings = embeddings;
    final AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
        .apiKey(properties.getAnthropic().getApiKey());
    if (!properties.getAnthropic().getWorkspaceId().isBlank()) {
      builder.putHeader("anthropic-workspace-id", properties.getAnthropic().getWorkspaceId());
    }
    this.client = builder.build();
  }

  @Override
  public String complete(final LlmRequest request) {
    final String model = properties.modelFor(request.role());
    final int maxTokens = Math.max(properties.maxTokensFor(request.role()), MIN_MAX_TOKENS);

    final MessageCreateParams.Builder params = MessageCreateParams.builder()
        .model(model)
        .maxTokens(maxTokens)
        .system(request.systemPrompt())
        .addUserMessage(request.userPrompt())
        // Caché automática del prefijo: los system prompts se repiten en cada llamada del rol.
        .cacheControl(CacheControlEphemeral.builder().build());
    // Haiku no admite esfuerzo: mandarlo le da un 400.
    if (!model.startsWith("claude-haiku")) {
      params.outputConfig(OutputConfig.builder()
          .effort(OutputConfig.Effort.of(properties.getAnthropic().getEffort()))
          .build());
    }
    if (FALLBACK_MODELS.contains(model)) {
      params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
          .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
    }

    final RequestOptions options = RequestOptions.builder()
        .timeout(Duration.ofMillis(properties.readTimeoutFor(request.role())))
        .build();

    final Message response;
    try {
      response = client.messages().create(params.build(), options);
    } catch (AnthropicException e) {
      throw new LlmClientException("Fallo llamando al LLM (anthropic, modelo " + model + ")", e);
    }

    final StopReason stopReason = response.stopReason().orElse(null);
    if (StopReason.REFUSAL.equals(stopReason)) {
      throw new LlmClientException("El modelo " + model + " rechazó la petición (rol "
          + request.role() + ")");
    }
    // El corte se comprueba ANTES de leer el contenido, igual que en el cliente compatible con
    // OpenAI: "respuesta vacía" despistaría sobre la causa real, que es el techo.
    if (StopReason.MAX_TOKENS.equals(stopReason)) {
      final String role = request.role() == null ? "sin rol" : request.role();
      final String message = "El modelo " + model + " agotó el tope de " + maxTokens
          + " tokens y su respuesta salió cortada (rol " + role
          + "). Sube maestro.llm.max-tokens-by-role." + role;
      if (request.requireComplete()) {
        throw new LlmTruncatedException(message);
      }
      log.warn("{}", message);
    }
    return extractText(response.content());
  }

  @Override
  public List<Double> embed(final String text, final String issueKey) {
    return embeddings.embed(text, issueKey);
  }

  /** Une los bloques de texto; los de razonamiento se descartan. */
  private String extractText(final List<ContentBlock> content) {
    final String text = content.stream()
        .flatMap(block -> block.text().stream())
        .map(TextBlock::text)
        .collect(Collectors.joining());
    if (text.isBlank()) {
      throw new LlmClientException("Respuesta del LLM con contenido vacío");
    }
    return text.trim();
  }
}

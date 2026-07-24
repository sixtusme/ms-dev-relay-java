package es.colorbaby.microservices.dev.relay.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Traduce la instrucción en texto libre de un comando a una intención de la lista cerrada
 * {@link CommandIntent}. Con la IA encendida lo decide el LLM devolviendo JSON; con la IA apagada
 * (o si el modelo falla o se inventa algo) cae a un filtro determinista por palabras clave.
 *
 * <p>La salida SIEMPRE se valida contra el enum: si no casa, es {@link CommandIntent#UNKNOWN} y
 * sixai pedirá aclaración en vez de actuar. El modelo nunca elige una acción fuera del catálogo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandIntentInterpreter {

  private static final String SYSTEM_PROMPT =
      "Eres Sixai. Te doy una orden escrita por una persona en una tarea de Jira y el estado de esa "
      + "tarea. Clasifícala en UNA de estas intenciones exactas: PROMOTE_TO_PROD (pasar a "
      + "producción), REVISE (corregir o cambiar algo de lo entregado), REDEPLOY (repetir el "
      + "despliegue sin cambios), STATUS (preguntar cómo va), CANCEL (abandonar), UNKNOWN (no se "
      + "entiende). Responde ÚNICAMENTE con JSON válido, sin texto ni comillas triples alrededor, "
      + "con esta forma: {\"intent\": \"UNA_DE_LAS_ANTERIORES\", \"detail\": \"qué se pide, en una "
      + "frase\"}. Si dudas o la orden es ambigua, responde UNKNOWN: es preferible preguntar a "
      + "actuar por error.";

  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final ObjectMapper objectMapper;

  /** Intención de una orden. Nunca null; ante la duda, {@link CommandIntent#UNKNOWN}. */
  public CommandIntent interpret(final SixaiCommand command) {
    if (llmProperties.isEnabled()) {
      final CommandIntent byLlm = interpretWithLlm(command);
      if (byLlm != null) {
        return byLlm;
      }
      log.warn("El LLM no clasificó el comando de {}; uso el fallback por keywords",
          command.issueKey());
    }
    return interpretWithKeywords(command);
  }

  private CommandIntent interpretWithLlm(final SixaiCommand command) {
    final String user = "Estado de la tarea: "
        + (command.issueStatus() == null ? "(desconocido)" : command.issueStatus())
        + "\n\nOrden:\n" + command.instruction();
    final String output;
    try {
      output = llmClient.complete(
          LlmRequest.of(SYSTEM_PROMPT, user, LlmRoles.ROUTER, command.issueKey()));
    } catch (RuntimeException e) {
      log.warn("Fallo del LLM interpretando el comando de {}: {}",
          command.issueKey(), e.getMessage());
      return null;
    }
    return parseIntent(output);
  }

  private CommandIntent parseIntent(final String output) {
    if (output == null || output.isBlank()) {
      return null;
    }
    final int start = output.indexOf('{');
    final int end = output.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return null;
    }
    final JsonNode root;
    try {
      root = objectMapper.readTree(output.substring(start, end + 1));
    } catch (JsonProcessingException e) {
      log.warn("Respuesta del intérprete no es JSON válido: {}", e.getMessage());
      return null;
    }
    final String value = root.path("intent").asText("");
    for (final CommandIntent intent : CommandIntent.values()) {
      if (intent.name().equalsIgnoreCase(value.strip())) {
        return intent;
      }
    }
    return null;
  }

  // Fallback sin IA: palabras clave sobre la orden, con el estado como desempate.
  private CommandIntent interpretWithKeywords(final SixaiCommand command) {
    final String text = command.instruction() == null
        ? "" : command.instruction().toLowerCase(Locale.ROOT);
    if (text.isBlank()) {
      return CommandIntent.UNKNOWN;
    }
    if (contains(text, "prod", "producción", "produccion", "promociona", "publica")) {
      return CommandIntent.PROMOTE_TO_PROD;
    }
    if (contains(text, "cancela", "aborta", "para", "detén", "deten")) {
      return CommandIntent.CANCEL;
    }
    if (contains(text, "cómo va", "como va", "estado", "status", "qué falta", "que falta")) {
      return CommandIntent.STATUS;
    }
    if (contains(text, "redespliega", "vuelve a desplegar", "redeploy")) {
      return CommandIntent.REDEPLOY;
    }
    if (contains(text, "cambia", "corrige", "arregla", "añade", "anade", "quita", "está mal",
        "esta mal", "revisa")) {
      return CommandIntent.REVISE;
    }
    return CommandIntent.UNKNOWN;
  }

  private static boolean contains(final String text, final String... needles) {
    for (final String needle : needles) {
      if (text.contains(needle)) {
        return true;
      }
    }
    return false;
  }
}

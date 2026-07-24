package es.colorbaby.microservices.dev.relay.insight;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Responde preguntas sobre lo que ha hecho sixai, de dos formas:
 *
 * <ul>
 *   <li><b>Sin IA</b>: se elige una consulta del catálogo y se ejecuta. Determinista.</li>
 *   <li><b>Con IA</b>: se escribe la pregunta en lenguaje natural y el modelo elige QUÉ consulta del
 *       catálogo responde mejor. El modelo <b>no escribe SQL</b>: solo devuelve un identificador que
 *       se valida contra el enum. Si no encaja ninguna, se pide aclaración en vez de inventar.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InsightService {

  private static final String ROUTER_PROMPT_HEAD =
      "Eres Sixai. Te doy una pregunta sobre tu propia actividad y una lista de consultas "
      + "disponibles. Elige la que mejor la responde. Responde ÚNICAMENTE con JSON válido, sin "
      + "texto alrededor: {\"query\": \"ID_DE_LA_LISTA\", \"issueKey\": \"CLAVE\"}. El campo "
      + "issueKey solo si la pregunta menciona una tarea concreta; si no, déjalo vacío. Si ninguna "
      + "consulta encaja, responde {\"query\": \"\"}. Consultas disponibles:\n";

  private final NamedParameterJdbcTemplate jdbc;
  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final ObjectMapper objectMapper;

  /** Catálogo para pintar los botones del panel. */
  public List<Map<String, Object>> catalog() {
    final List<Map<String, Object>> catalog = new ArrayList<>();
    for (final InsightQuery query : InsightQuery.values()) {
      catalog.add(Map.of(
          "id", query.name(),
          "label", query.label(),
          "description", query.description(),
          "requiresIssue", query.requiresIssue()));
    }
    return catalog;
  }

  /** Ejecuta una consulta del catálogo. */
  public InsightResult run(final InsightQuery query, final String issueKey) {
    if (query.requiresIssue() && (issueKey == null || issueKey.isBlank())) {
      return InsightResult.note("Esa consulta necesita que indiques una tarea "
          + "(selecciona una sesión o menciona su clave).");
    }
    final Map<String, Object> params = new HashMap<>();
    if (query.requiresIssue()) {
      params.put("issueKey", issueKey.strip());
    }
    try {
      final List<List<String>> rows = jdbc.query(query.sql(), params, (rs, rowNum) -> {
        final int columns = rs.getMetaData().getColumnCount();
        final List<String> row = new ArrayList<>(columns);
        for (int i = 1; i <= columns; i++) {
          final Object value = rs.getObject(i);
          row.add(value == null ? "" : value.toString());
        }
        return row;
      });
      return InsightResult.of(query, rows);
    } catch (RuntimeException e) {
      log.error("Error ejecutando la consulta {}: {}", query, e.getMessage());
      return InsightResult.note("No se pudo ejecutar la consulta.");
    }
  }

  /** Responde una pregunta en lenguaje natural eligiendo una consulta del catálogo. */
  public InsightResult ask(final String question, final String issueKey) {
    if (question == null || question.isBlank()) {
      return InsightResult.note("Escribe una pregunta.");
    }
    if (!llmProperties.isEnabled()) {
      return InsightResult.note("La IA está apagada. Puedes usar las consultas de la lista, que "
          + "funcionan sin ella.");
    }
    final InsightQuery chosen = route(question);
    if (chosen == null) {
      return InsightResult.note("No he sabido a qué consulta corresponde. Prueba con una de la "
          + "lista, o pregúntame por tareas, tiempos, fallos, comandos o uso de IA.");
    }
    return run(chosen, issueKey == null || issueKey.isBlank() ? issueFrom(question) : issueKey);
  }

  /** El modelo elige un identificador del catálogo; cualquier otra cosa se descarta. */
  private InsightQuery route(final String question) {
    final StringBuilder prompt = new StringBuilder(ROUTER_PROMPT_HEAD);
    for (final InsightQuery query : InsightQuery.values()) {
      prompt.append("- ").append(query.name()).append(": ").append(query.description()).append('\n');
    }
    final String output;
    try {
      output = llmClient.complete(
          LlmRequest.of(prompt.toString(), question, LlmRoles.ROUTER, null));
    } catch (RuntimeException e) {
      log.warn("Fallo del LLM enrutando la pregunta: {}", e.getMessage());
      return null;
    }
    final String value = parseQueryId(output);
    for (final InsightQuery query : InsightQuery.values()) {
      if (query.name().equalsIgnoreCase(value)) {
        return query;
      }
    }
    return null;
  }

  private String parseQueryId(final String output) {
    if (output == null || output.isBlank()) {
      return "";
    }
    final int start = output.indexOf('{');
    final int end = output.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return "";
    }
    try {
      final JsonNode root = objectMapper.readTree(output.substring(start, end + 1));
      return root.path("query").asText("").strip();
    } catch (JsonProcessingException e) {
      log.warn("Respuesta del enrutador no es JSON válido: {}", e.getMessage());
      return "";
    }
  }

  /** Clave de tarea mencionada en la pregunta (ej. "cómo fue USA-938"), si la hay. */
  private static String issueFrom(final String question) {
    for (final String token : question.split("[^A-Za-z0-9-]+")) {
      if (token.matches("[A-Za-z]{2,10}-\\d+")) {
        return token.toUpperCase(Locale.ROOT);
      }
    }
    return null;
  }
}

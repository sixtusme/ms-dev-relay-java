package es.colorbaby.microservices.dev.relay.insight;

import java.util.List;

/**
 * Resultado de una consulta, en forma de tabla para que el panel lo pinte sin saber de qué consulta
 * viene. {@code note} lleva un mensaje cuando no hay tabla que enseñar (no se entendió la pregunta,
 * falta indicar la tarea…).
 */
public record InsightResult(String query, String title, List<String> columns,
    List<List<String>> rows, String note) {

  /** Resultado con datos. */
  public static InsightResult of(final InsightQuery query, final List<List<String>> rows) {
    return new InsightResult(query.name(), query.label(), query.columns(), rows, null);
  }

  /** Resultado sin tabla: solo un mensaje para la persona. */
  public static InsightResult note(final String note) {
    return new InsightResult(null, null, List.of(), List.of(), note);
  }
}

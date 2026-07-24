package es.colorbaby.microservices.dev.relay.insight;

import java.util.List;

/**
 * Catálogo CERRADO de consultas sobre la actividad de sixai.
 *
 * <p>El SQL vive aquí, junto a su identificador, escrito a mano y con parámetros ligados. El LLM
 * solo puede <b>elegir uno de estos identificadores</b>: nunca compone SQL. Es la misma regla de
 * "herramientas tipadas" que rige los comandos, aplicada a las consultas — si se le deja escribir
 * SQL, tarde o temprano lee algo que no debe.
 *
 * <p>Para añadir una consulta se añade una constante aquí y queda disponible en el panel; no hay
 * ningún otro sitio que tocar.
 */
public enum InsightQuery {

  RECENT_TASKS(
      "Últimas tareas",
      "Las tareas más recientes con quién las pidió y cuánto tardaron.",
      false,
      List.of("Tarea", "Título", "Estado", "Pedida por", "Duración (s)", "Inicio"),
      """
      SELECT issue_key, COALESCE(title, ''), status, COALESCE(requested_by_name, ''),
             COALESCE(ROUND(duration_ms / 1000), 0), DATE_FORMAT(started_at, '%Y-%m-%d %H:%i')
      FROM task_run
      ORDER BY started_at DESC
      LIMIT 25
      """),

  TASK_TIMELINE(
      "Qué pasó con una tarea",
      "La línea de tiempo completa de una tarea, hito a hito.",
      true,
      List.of("Cuándo", "Hito", "Actor", "Detalle"),
      """
      SELECT DATE_FORMAT(occurred_at, '%Y-%m-%d %H:%i:%s'), type, COALESCE(actor, ''),
             COALESCE(detail, '')
      FROM task_event
      WHERE issue_key = :issueKey
      ORDER BY occurred_at ASC
      LIMIT 200
      """),

  TASK_LLM_USAGE(
      "Uso de IA de una tarea",
      "Cuántas llamadas al modelo costó una tarea y con qué latencia.",
      true,
      List.of("Rol", "Llamadas", "Latencia media (ms)", "Fallos"),
      """
      SELECT role, COUNT(*), COALESCE(ROUND(AVG(latency_ms)), 0),
             SUM(CASE WHEN success THEN 0 ELSE 1 END)
      FROM llm_call
      WHERE issue_key = :issueKey
      GROUP BY role
      ORDER BY COUNT(*) DESC
      """),

  SLOWEST_TASKS(
      "Tareas más lentas",
      "Las tareas que más tiempo llevaron de principio a fin.",
      false,
      List.of("Tarea", "Título", "Duración (s)", "Estado"),
      """
      SELECT issue_key, COALESCE(title, ''), ROUND(duration_ms / 1000), status
      FROM task_run
      WHERE duration_ms IS NOT NULL
      ORDER BY duration_ms DESC
      LIMIT 15
      """),

  FAILURES(
      "Qué ha fallado",
      "Los fallos registrados, del más reciente al más antiguo.",
      false,
      List.of("Tarea", "Actor", "Detalle", "Cuándo"),
      """
      SELECT issue_key, COALESCE(actor, ''), COALESCE(detail, ''),
             DATE_FORMAT(occurred_at, '%Y-%m-%d %H:%i')
      FROM task_event
      WHERE type = 'FAILED'
      ORDER BY occurred_at DESC
      LIMIT 25
      """),

  LLM_BY_ROLE(
      "Uso de IA por rol",
      "Qué rol del agente consume más llamadas y cuál se ha vuelto lento.",
      false,
      List.of("Rol", "Llamadas", "Latencia media (ms)", "Fallos"),
      """
      SELECT role, COUNT(*), COALESCE(ROUND(AVG(latency_ms)), 0),
             SUM(CASE WHEN success THEN 0 ELSE 1 END)
      FROM llm_call
      GROUP BY role
      ORDER BY COUNT(*) DESC
      """),

  RECENT_COMMANDS(
      "Comandos recibidos",
      "Quién ha ordenado qué a sixai y si tenía permiso.",
      false,
      List.of("Tarea", "Quién", "Intención", "Autorizado", "Orden", "Cuándo"),
      """
      SELECT issue_key, COALESCE(author_name, ''), COALESCE(intent, ''),
             CASE WHEN authorized IS NULL THEN '' WHEN authorized THEN 'sí' ELSE 'no' END,
             COALESCE(raw_text, ''), DATE_FORMAT(received_at, '%Y-%m-%d %H:%i')
      FROM command_execution
      ORDER BY received_at DESC
      LIMIT 25
      """);

  private final String label;
  private final String description;
  private final boolean requiresIssue;
  private final List<String> columns;
  private final String sql;

  InsightQuery(final String label, final String description, final boolean requiresIssue,
      final List<String> columns, final String sql) {
    this.label = label;
    this.description = description;
    this.requiresIssue = requiresIssue;
    this.columns = columns;
    this.sql = sql;
  }

  public String label() {
    return label;
  }

  public String description() {
    return description;
  }

  /** True si la consulta necesita que se indique una tarea concreta. */
  public boolean requiresIssue() {
    return requiresIssue;
  }

  public List<String> columns() {
    return columns;
  }

  public String sql() {
    return sql;
  }
}

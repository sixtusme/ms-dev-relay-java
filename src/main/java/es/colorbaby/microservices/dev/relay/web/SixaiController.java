package es.colorbaby.microservices.dev.relay.web;

import es.colorbaby.microservices.dev.relay.approval.ApprovalService;
import es.colorbaby.microservices.dev.relay.chat.ChatService;
import es.colorbaby.microservices.dev.relay.insight.InsightQuery;
import es.colorbaby.microservices.dev.relay.insight.InsightResult;
import es.colorbaby.microservices.dev.relay.insight.InsightService;
import es.colorbaby.microservices.dev.relay.monitor.ActiveTaskDto;
import es.colorbaby.microservices.dev.relay.monitor.TaskDetailDto;
import es.colorbaby.microservices.dev.relay.monitor.TaskMonitorService;
import es.colorbaby.microservices.dev.relay.report.ReportDto;
import es.colorbaby.microservices.dev.relay.report.ReportQueryService;
import es.colorbaby.microservices.dev.relay.session.SessionQueryService;
import es.colorbaby.microservices.dev.relay.session.SixaiSessionDto;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de control del panel {@code /sixai} del front de docs. La auth la hace el security-gateway
 * (ruta {@code /sixai/**} → este servicio); aquí no hay seguridad propia. Es la compuerta humana:
 * el front lista las sesiones y, al aprobar una, dispara el merge a develop (Fase 3).
 */
@RestController
@RequestMapping("/sixai")
@RequiredArgsConstructor
public class SixaiController {

  private final SessionQueryService sessionQueryService;
  private final ApprovalService approvalService;
  private final ReportQueryService reportQueryService;
  private final InsightService insightService;
  private final ChatService chatService;
  private final TaskMonitorService taskMonitorService;

  /**
   * Tareas que sixai tiene en curso ahora mismo, con la etapa concreta de cada una. Es lo que
   * refresca el panel flotante, así que es una lectura barata: nada de llamar a GitHub ni a Jenkins.
   */
  @GetMapping("/tasks/active")
  public List<ActiveTaskDto> activeTasks() {
    return taskMonitorService.active();
  }

  /** El avance de una tarea al detalle: línea de tiempo completa y despliegues. */
  @GetMapping("/tasks/{issueKey}")
  public ResponseEntity<TaskDetailDto> taskDetail(@PathVariable final String issueKey) {
    return taskMonitorService.detail(issueKey)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** Sesiones de sixai en curso (reconstruidas de GitHub). */
  @GetMapping("/sessions")
  public List<SixaiSessionDto> sessions() {
    return sessionQueryService.listSessions();
  }

  /**
   * Aprueba una issue: mergea sus PRs a develop y arranca el despliegue a PRE.
   *
   * <p>{@code X-Username} la pone el security-gateway al validar el JWT; aquí solo se lee. Es la
   * compuerta que autoriza meter código en develop, así que queda registrado <b>quién</b> la abrió y
   * se ve en la línea de tiempo de la tarea.
   */
  @PostMapping("/issues/{issueKey}/approve")
  public ResponseEntity<Void> approve(@PathVariable final String issueKey,
      @RequestHeader(value = "X-Username", required = false) final String approvedBy) {
    approvalService.approve(issueKey, approvedBy);
    return ResponseEntity.accepted().build();
  }

  /**
   * Informes y recursos indexados. Se sirve desde la base de datos (rápido); el contenido solo se
   * baja del FTP/SFTP al abrir uno.
   *
   * @param issueKey si se indica, solo los de esa tarea
   */
  @GetMapping("/reports")
  public List<ReportDto> reports(@RequestParam(required = false) final String issueKey) {
    return reportQueryService.list(issueKey);
  }

  /** Contenido de un informe (markdown en texto plano). */
  @GetMapping(value = "/reports/{id}/content", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> reportContent(@PathVariable final Long id) {
    return reportQueryService.content(id)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /** Consultas disponibles sobre la actividad de sixai (para pintar la lista del panel). */
  @GetMapping("/insights/catalog")
  public List<Map<String, Object>> insightsCatalog() {
    return insightService.catalog();
  }

  /**
   * Ejecuta una consulta. Con {@code query} se ejecuta esa del catálogo (sin IA); con
   * {@code question} se deja que el modelo elija cuál del catálogo responde mejor.
   */
  @PostMapping("/insights/query")
  public InsightResult insightsQuery(@RequestBody final InsightRequest request) {
    if (request.query() != null && !request.query().isBlank()) {
      try {
        return insightService.run(InsightQuery.valueOf(request.query()), request.issueKey());
      } catch (IllegalArgumentException e) {
        return InsightResult.note("Esa consulta no existe.");
      }
    }
    return insightService.ask(request.question(), request.issueKey());
  }

  /** Petición del panel: o una consulta del catálogo, o una pregunta en lenguaje natural. */
  public record InsightRequest(String query, String question, String issueKey) {
  }

  /** Historial del chat de una tarea. */
  @GetMapping("/chat")
  public List<ChatMessageDto> chatHistory(@RequestParam(required = false) final String issueKey) {
    return chatService.history(issueKey).stream()
        .map(m -> new ChatMessageDto(m.getRole(), m.getContent(), m.getCreatedAt().toString()))
        .toList();
  }

  /** Envía un mensaje al chat y devuelve la respuesta. Solo consulta: no ejecuta acciones. */
  @PostMapping("/chat")
  public ChatMessageDto chatSend(@RequestBody final ChatRequest request) {
    final var answer = chatService.send(request.issueKey(), request.message());
    return new ChatMessageDto(answer.getRole(), answer.getContent(),
        answer.getCreatedAt().toString());
  }

  /** Un mensaje del chat tal y como lo pinta el panel. */
  public record ChatMessageDto(String role, String content, String createdAt) {
  }

  /** Mensaje enviado desde el panel. */
  public record ChatRequest(String issueKey, String message) {
  }
}

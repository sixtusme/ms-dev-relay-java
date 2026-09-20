package es.colorbaby.microservices.dev.relay.api;

import es.colorbaby.microservices.dev.relay.control.approval.ApprovalService;
import es.colorbaby.microservices.dev.relay.openapi.api.SixaiApiDelegate;
import es.colorbaby.microservices.dev.relay.openapi.model.ActiveTaskDto;
import es.colorbaby.microservices.dev.relay.openapi.model.ChatMessageDto;
import es.colorbaby.microservices.dev.relay.openapi.model.ChatRequestDto;
import es.colorbaby.microservices.dev.relay.openapi.model.InsightCatalogItemDto;
import es.colorbaby.microservices.dev.relay.openapi.model.InsightRequestDto;
import es.colorbaby.microservices.dev.relay.openapi.model.InsightResultDto;
import es.colorbaby.microservices.dev.relay.openapi.model.ReportDto;
import es.colorbaby.microservices.dev.relay.openapi.model.SixaiPrDto;
import es.colorbaby.microservices.dev.relay.openapi.model.SixaiSessionDto;
import es.colorbaby.microservices.dev.relay.openapi.model.TaskDeploymentDto;
import es.colorbaby.microservices.dev.relay.openapi.model.TaskDetailDto;
import es.colorbaby.microservices.dev.relay.openapi.model.TaskEventDto;
import es.colorbaby.microservices.dev.relay.panel.chat.ChatService;
import es.colorbaby.microservices.dev.relay.panel.insight.InsightQuery;
import es.colorbaby.microservices.dev.relay.panel.insight.InsightService;
import es.colorbaby.microservices.dev.relay.panel.monitor.TaskMonitorService;
import es.colorbaby.microservices.dev.relay.panel.report.ReportQueryService;
import es.colorbaby.microservices.dev.relay.panel.session.SessionQueryService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Implementación del delegate del contrato para el panel de {@code /sixai}. El {@code @Controller}
 * real es el {@code SixaiApiController} generado por el openapi-generator, que hace el
 * {@code @RequestMapping} y delega en este bean (mismo patrón que
 * {@link WebhooksApiDelegateImpl}). El contrato (paths de esta app + esquemas compartidos con la
 * lib) vive en {@code resources/static/openapi.yaml}.
 *
 * <p>Los servicios de {@code panel/*} siguen devolviendo sus propios tipos internos; esta clase es
 * el único punto que los traduce a los modelos generados por el contrato — así un cambio en el
 * contrato HTTP no obliga a tocar la lógica de negocio, y viceversa.
 */
@Component
@RequiredArgsConstructor
public class SixaiApiDelegateImpl implements SixaiApiDelegate {

  private final SessionQueryService sessionQueryService;
  private final ApprovalService approvalService;
  private final ReportQueryService reportQueryService;
  private final InsightService insightService;
  private final ChatService chatService;
  private final TaskMonitorService taskMonitorService;

  @Override
  public ResponseEntity<List<ActiveTaskDto>> listActiveTasks() {
    final List<ActiveTaskDto> tasks = taskMonitorService.active().stream()
        .map(task -> new ActiveTaskDto()
            .issueKey(task.issueKey())
            .title(task.title())
            .stage(task.stage())
            .stageKey(task.stageKey())
            .detail(task.detail())
            .startedAt(task.startedAt())
            .elapsedMs(task.elapsedMs())
            .prCount(task.prCount()))
        .toList();
    return ResponseEntity.ok(tasks);
  }

  @Override
  public ResponseEntity<TaskDetailDto> getTaskDetail(final String issueKey) {
    return taskMonitorService.detail(issueKey)
        .map(detail -> {
          final TaskDetailDto dto = new TaskDetailDto()
              .issueKey(detail.issueKey())
              .title(detail.title())
              .epic(detail.epic())
              .status(detail.status())
              .stage(detail.stage())
              .stageKey(detail.stageKey())
              .requestedBy(detail.requestedBy())
              .startedAt(detail.startedAt())
              .durationMs(detail.durationMs());
          detail.events().forEach(event -> dto.addEventsItem(new TaskEventDto()
              .type(event.type())
              .actor(event.actor())
              .detail(event.detail())
              .occurredAt(event.occurredAt())));
          detail.deployments().forEach(deployment -> dto.addDeploymentsItem(new TaskDeploymentDto()
              .repo(deployment.repo())
              .environment(deployment.environment())
              .phase(deployment.phase())
              .stage(deployment.stage())
              .status(deployment.status())
              .version(deployment.version())));
          return dto;
        })
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @Override
  public ResponseEntity<List<SixaiSessionDto>> listSixaiSessions() {
    final List<SixaiSessionDto> sessions = sessionQueryService.listSessions().stream()
        .map(session -> {
          final SixaiSessionDto dto = new SixaiSessionDto()
              .issueKey(session.issueKey())
              .title(session.title());
          session.prs().forEach(pr -> dto.addPrsItem(new SixaiPrDto()
              .repo(pr.repo())
              .number(pr.number())
              .url(pr.url())
              .branch(pr.branch())
              .base(pr.base())
              .verification(SixaiPrDto.VerificationEnum.fromValue(pr.verification()))
              .verificationDetail(pr.verificationDetail())));
          return dto;
        })
        .toList();
    return ResponseEntity.ok(sessions);
  }

  @Override
  public ResponseEntity<Void> approveIssue(final String issueKey, final String xUsername) {
    approvalService.approve(issueKey, xUsername);
    return ResponseEntity.accepted().build();
  }

  @Override
  public ResponseEntity<List<ReportDto>> listReports(final String issueKey) {
    final List<ReportDto> reports = reportQueryService.list(issueKey).stream()
        .map(report -> new ReportDto()
            .id(report.id())
            .issueKey(report.issueKey())
            .kind(ReportDto.KindEnum.fromValue(report.kind()))
            .title(report.title())
            .format(report.format())
            .sizeBytes(report.sizeBytes())
            .generatedAt(report.generatedAt()))
        .toList();
    return ResponseEntity.ok(reports);
  }

  @Override
  public ResponseEntity<String> getReportContent(final Long id) {
    return reportQueryService.content(id)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @Override
  public ResponseEntity<List<InsightCatalogItemDto>> listInsightsCatalog() {
    final List<InsightCatalogItemDto> catalog = insightService.catalog().stream()
        .map(item -> new InsightCatalogItemDto()
            .id(String.valueOf(item.get("id")))
            .label(String.valueOf(item.get("label")))
            .description(String.valueOf(item.get("description")))
            .requiresIssue(Boolean.TRUE.equals(item.get("requiresIssue"))))
        .toList();
    return ResponseEntity.ok(catalog);
  }

  @Override
  public ResponseEntity<InsightResultDto> queryInsights(final InsightRequestDto request) {
    final var result = ask(request);
    final InsightResultDto dto = new InsightResultDto()
        .query(result.query())
        .title(result.title())
        .columns(result.columns())
        .note(result.note());
    result.rows().forEach(dto::addRowsItem);
    return ResponseEntity.ok(dto);
  }

  private es.colorbaby.microservices.dev.relay.panel.insight.InsightResult ask(
      final InsightRequestDto request) {
    if (request.getQuery() != null && !request.getQuery().isBlank()) {
      try {
        return insightService.run(InsightQuery.valueOf(request.getQuery()), request.getIssueKey());
      } catch (IllegalArgumentException e) {
        return es.colorbaby.microservices.dev.relay.panel.insight.InsightResult
            .note("Esa consulta no existe.");
      }
    }
    return insightService.ask(request.getQuestion(), request.getIssueKey());
  }

  @Override
  public ResponseEntity<List<ChatMessageDto>> listChatHistory(final String issueKey) {
    final List<ChatMessageDto> history = chatService.history(issueKey).stream()
        .map(message -> new ChatMessageDto()
            .role(message.getRole())
            .content(message.getContent())
            .createdAt(message.getCreatedAt().toString()))
        .toList();
    return ResponseEntity.ok(history);
  }

  @Override
  public ResponseEntity<ChatMessageDto> sendChatMessage(final ChatRequestDto request) {
    final var answer = chatService.send(request.getIssueKey(), request.getMessage());
    final ChatMessageDto dto = new ChatMessageDto()
        .role(answer.getRole())
        .content(answer.getContent())
        .createdAt(answer.getCreatedAt().toString());
    return ResponseEntity.ok(dto);
  }
}

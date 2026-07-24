package es.colorbaby.microservices.dev.relay.chat;

import es.colorbaby.microservices.dev.relay.activity.TaskEvent;
import es.colorbaby.microservices.dev.relay.activity.TaskEventRepository;
import es.colorbaby.microservices.dev.relay.activity.TaskRun;
import es.colorbaby.microservices.dev.relay.activity.TaskRunRepository;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import es.colorbaby.microservices.dev.relay.report.Report;
import es.colorbaby.microservices.dev.relay.report.ReportRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * El chat del panel: conversar sobre lo que ha hecho sixai.
 *
 * <p><b>Consulta y explica; no actúa.</b> Es deliberado: ordenar (desplegar, promocionar, corregir)
 * se hace desde Jira con {@code /sixai}, donde queda trazado y autorizado. Aquí solo se razona sobre
 * lo ya ocurrido.
 *
 * <p>La respuesta se <b>fundamenta en lo registrado</b> (la tarea, su línea de tiempo y sus
 * informes), que se le pasa al modelo como contexto. Sin ese anclaje el chat inventaría; con él,
 * cuando no hay dato, puede decir que no lo sabe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatService {

  private static final String SYSTEM_PROMPT =
      "Eres Sixai y estás respondiendo preguntas sobre TU PROPIO trabajo ya realizado. Te doy el "
      + "contexto registrado (la tarea, su línea de tiempo y sus informes) y la conversación. "
      + "Responde en español, breve y concreto, APOYÁNDOTE SOLO en ese contexto. Si el contexto no "
      + "contiene la respuesta, dilo claramente en vez de suponer. NUNCA digas que vas a desplegar, "
      + "promocionar, corregir ni tocar nada: aquí solo explicas. Si te piden una acción, indica "
      + "que se pide desde la tarea de Jira con /sixai.";

  /** Últimos eventos que se dan como contexto; suficiente para explicar sin saturar el prompt. */
  private static final int MAX_EVENTS = 40;

  /** Últimos mensajes de la conversación que se recuerdan. */
  private static final int MAX_HISTORY = 12;

  private final ChatConversationRepository conversations;
  private final ChatMessageRepository messages;
  private final TaskRunRepository tasks;
  private final TaskEventRepository events;
  private final ReportRepository reports;
  private final LlmClient llmClient;
  private final LlmProperties llmProperties;

  /** Mensajes de la conversación viva de una tarea (vacío si aún no hay). */
  @Transactional(readOnly = true)
  public List<ChatMessage> history(final String issueKey) {
    return conversations.findFirstByIssueKeyOrderByCreatedAtDesc(issueKey)
        .map(c -> messages.findByConversationIdOrderByCreatedAtAsc(c.getId()))
        .orElseGet(List::of);
  }

  /**
   * Envía un mensaje y devuelve la respuesta, dejando ambos guardados.
   *
   * @param issueKey tarea sobre la que se conversa (puede ser null para preguntas generales)
   * @param question lo que pregunta la persona
   */
  @Transactional
  public ChatMessage send(final String issueKey, final String question) {
    final ChatConversation conversation = conversationFor(issueKey);
    messages.save(new ChatMessage(conversation.getId(), ChatMessage.USER,
        truncate(question, ChatMessage.CONTENT_MAX)));

    final String answer = answer(issueKey, conversation);
    return messages.save(new ChatMessage(conversation.getId(), ChatMessage.ASSISTANT,
        truncate(answer, ChatMessage.CONTENT_MAX)));
  }

  private String answer(final String issueKey, final ChatConversation conversation) {
    if (!llmProperties.isEnabled()) {
      return "La IA está apagada, así que no puedo razonar sobre el historial. En la pestaña "
          + "Consultar tienes los datos en crudo, que funcionan sin ella.";
    }
    try {
      final String prompt = context(issueKey) + "\n\n## Conversación\n"
          + conversationText(conversation.getId());
      return llmClient.complete(
          LlmRequest.of(SYSTEM_PROMPT, prompt, LlmRoles.PLANNER, issueKey));
    } catch (RuntimeException e) {
      log.warn("Fallo del LLM respondiendo el chat de {}: {}", issueKey, e.getMessage());
      return "No he podido responder ahora mismo.";
    }
  }

  /** Lo registrado sobre la tarea: es lo que ancla la respuesta y evita que se invente. */
  private String context(final String issueKey) {
    if (issueKey == null || issueKey.isBlank()) {
      return "## Contexto\n(sin tarea seleccionada; responde solo si la pregunta es general)";
    }
    final StringBuilder sb = new StringBuilder("## Contexto de ").append(issueKey).append('\n');

    tasks.findByIssueKeyOrderByStartedAtDesc(issueKey).stream().findFirst().ifPresent(task ->
        sb.append("- Título: ").append(nullSafe(task.getTitle())).append('\n')
          .append("- Épica: ").append(nullSafe(task.getEpic())).append('\n')
          .append("- Estado: ").append(nullSafe(task.getStatus())).append('\n')
          .append("- Pedida por: ").append(nullSafe(task.getRequestedByName())).append('\n')
          .append("- Duración (ms): ").append(task.getDurationMs() == null
              ? "en curso" : task.getDurationMs()).append('\n'));

    final List<TaskEvent> timeline = events.findByIssueKeyOrderByOccurredAtAsc(issueKey);
    if (!timeline.isEmpty()) {
      sb.append("\n### Línea de tiempo\n");
      timeline.stream().skip(Math.max(0, timeline.size() - MAX_EVENTS)).forEach(event ->
          sb.append("- ").append(event.getOccurredAt()).append(' ').append(event.getType())
            .append(" (").append(nullSafe(event.getActor())).append(") ")
            .append(nullSafe(event.getDetail())).append('\n'));
    }

    final List<Report> files = reports.findByIssueKeyOrderByGeneratedAtDesc(issueKey);
    if (!files.isEmpty()) {
      sb.append("\n### Ficheros\n");
      files.forEach(report ->
          sb.append("- ").append(report.getKind()).append(": ").append(report.getTitle())
            .append('\n'));
    }
    return sb.toString();
  }

  private String conversationText(final Long conversationId) {
    final List<ChatMessage> all = messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
    final List<String> lines = new ArrayList<>();
    all.stream().skip(Math.max(0, all.size() - MAX_HISTORY)).forEach(message ->
        lines.add((ChatMessage.USER.equals(message.getRole()) ? "Persona: " : "Sixai: ")
            + message.getContent()));
    return String.join("\n", lines);
  }

  private ChatConversation conversationFor(final String issueKey) {
    if (issueKey == null || issueKey.isBlank()) {
      return conversations.save(new ChatConversation(null, "Consulta general"));
    }
    return conversations.findFirstByIssueKeyOrderByCreatedAtDesc(issueKey)
        .orElseGet(() -> conversations.save(new ChatConversation(issueKey, issueKey)));
  }

  private static String nullSafe(final String value) {
    return value == null || value.isBlank() ? "—" : value;
  }

  private static String truncate(final String value, final int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}

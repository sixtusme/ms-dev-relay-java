package es.colorbaby.microservices.dev.relay.report;

import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.activity.TaskRun;
import es.colorbaby.microservices.dev.relay.config.ReportStorageProperties;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraAttachmentDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFields;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Gestiona la carpeta de cada tarea en el FTP/SFTP y el índice de sus ficheros.
 *
 * <p>Al arrancar una tarea se crea {@code /reports/<CODIGO>-<titulo>} y se copian ahí los adjuntos
 * que puso quien creó la tarea (capturas, .txt…). Cuando el coder termina, se publica el informe
 * {@code .md} en esa misma carpeta. Cada fichero se indexa en base de datos: el panel lista contra
 * la base y solo baja el contenido cuando alguien abre uno.
 *
 * <p>Todo es best-effort: si el FTP no responde, se loguea y la tarea sigue. Un informe que falta
 * es un incordio; una tarea que se queda a medias por no poder escribir un fichero, no.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportService {

  /** Tope de longitud del nombre de carpeta, para no generar rutas absurdas. */
  private static final int SLUG_MAX = 60;

  private final ReportStorage storage;
  private final ReportRepository reports;
  private final JiraClient jiraClient;
  private final TaskRecorder taskRecorder;
  private final ReportStorageProperties properties;

  /**
   * Prepara la carpeta de la tarea y copia en ella los adjuntos de Jira.
   *
   * @return la carpeta remota creada, o null si el almacén está apagado o falló
   */
  public String prepareFolder(final JiraIssueDto issue) {
    if (!properties.isEnabled() || issue == null || issue.getKey() == null) {
      return null;
    }
    final String folder = folderFor(issue);
    try {
      storage.ensureFolder(folder);
      log.info("Carpeta de informes lista: {}", folder);
      copyAttachments(issue, folder);
      return folder;
    } catch (RuntimeException e) {
      log.error("No se pudo preparar la carpeta {} : {}", folder, e.getMessage());
      return null;
    }
  }

  /**
   * Publica el informe de la tarea, ya resuelta por el coder.
   *
   * @param issue    la tarea
   * @param markdown contenido del informe
   */
  public void publishReport(final JiraIssueDto issue, final String markdown) {
    if (!properties.isEnabled() || issue == null || markdown == null || markdown.isBlank()) {
      return;
    }
    final String folder = folderFor(issue);
    final String fileName = issue.getKey() + "-informe.md";
    final byte[] content = markdown.getBytes(StandardCharsets.UTF_8);
    try {
      storage.ensureFolder(folder);
      storage.upload(folder, fileName, content);
      index(issue.getKey(), Report.KIND_DELIVERY, "Informe de " + issue.getKey(),
          folder, fileName, content.length, "md");
      log.info("Informe publicado: {}/{}", folder, fileName);
    } catch (RuntimeException e) {
      log.error("No se pudo publicar el informe de {}: {}", issue.getKey(), e.getMessage());
    }
  }

  /** Carpeta remota de una tarea: {@code /reports/<CODIGO>-<titulo>}. */
  public String folderFor(final JiraIssueDto issue) {
    final JiraIssueDtoFields fields = issue.getFields();
    final String title = fields == null ? null : fields.getSummary();
    final String base = properties.getBaseDirectory().replaceAll("/+$", "");
    final String slug = slug(title);
    return slug.isBlank() ? base + "/" + issue.getKey() : base + "/" + issue.getKey() + "-" + slug;
  }

  private void copyAttachments(final JiraIssueDto issue, final String folder) {
    final JiraIssueDtoFields fields = issue.getFields();
    final List<JiraAttachmentDto> attachments = fields == null ? null : fields.getAttachment();
    if (attachments == null || attachments.isEmpty()) {
      return;
    }
    for (final JiraAttachmentDto attachment : attachments) {
      if (attachment.getContent() == null || attachment.getFilename() == null) {
        continue;
      }
      try {
        final byte[] content = jiraClient.downloadAttachment(attachment.getContent());
        storage.upload(folder, attachment.getFilename(), content);
        index(issue.getKey(), Report.KIND_ATTACHMENT, attachment.getFilename(), folder,
            attachment.getFilename(), content.length, extensionOf(attachment.getFilename()));
        log.info("Adjunto copiado a {}: {}", folder, attachment.getFilename());
      } catch (RuntimeException e) {
        log.warn("No se pudo copiar el adjunto {} de {}: {}", attachment.getFilename(),
            issue.getKey(), e.getMessage());
      }
    }
  }

  private void index(final String issueKey, final String kind, final String title,
      final String folder, final String fileName, final long size, final String format) {
    try {
      final Report report = new Report();
      report.setIssueKey(issueKey);
      report.setTaskRunId(taskRecorder.current(issueKey).map(TaskRun::getId).orElse(null));
      report.setKind(kind);
      report.setTitle(title);
      report.setStorageUri(properties.getProtocol() + "://" + properties.getHost() + folder
          + "/" + fileName);
      report.setFormat(format);
      report.setSizeBytes(size);
      report.setGeneratedBy("sixai");
      reports.save(report);
    } catch (RuntimeException e) {
      log.warn("No se pudo indexar {} de {}: {}", fileName, issueKey, e.getMessage());
    }
  }

  private static String extensionOf(final String fileName) {
    final int dot = fileName.lastIndexOf('.');
    return dot > 0 && dot < fileName.length() - 1 ? fileName.substring(dot + 1) : "bin";
  }

  /** Nombre de carpeta seguro: sin acentos, sin espacios y sin caracteres raros. */
  private static String slug(final String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    final String plain = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("(^-|-$)", "");
    return plain.length() <= SLUG_MAX ? plain : plain.substring(0, SLUG_MAX)
        .replaceAll("-$", "");
  }
}

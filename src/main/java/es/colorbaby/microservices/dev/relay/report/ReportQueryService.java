package es.colorbaby.microservices.dev.relay.report;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Lo que consume el panel de informes: el listado sale de la base de datos (instantáneo) y el
 * contenido solo se baja del FTP/SFTP cuando alguien abre un fichero concreto.
 *
 * <p>Ese reparto es a propósito: leer el FTP en cada carga del panel sería lento y no permitiría
 * buscar. El índice en base de datos sí.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportQueryService {

  private final ReportRepository reports;
  private final ReportStorage storage;

  /** Fichas de los informes; de una tarea concreta si se indica, o los últimos si no. */
  public List<ReportDto> list(final String issueKey) {
    final List<Report> found = issueKey == null || issueKey.isBlank()
        ? reports.findTop50ByOrderByGeneratedAtDesc()
        : reports.findByIssueKeyOrderByGeneratedAtDesc(issueKey);
    return found.stream().map(ReportQueryService::toDto).toList();
  }

  /** Contenido de un informe, bajándolo del almacén remoto. */
  public Optional<String> content(final Long id) {
    return reports.findById(id).flatMap(report -> {
      try {
        final String uri = report.getStorageUri();
        final int slash = uri.lastIndexOf('/');
        final String fileName = uri.substring(slash + 1);
        final String folder = folderOf(uri.substring(0, slash));
        return Optional.of(new String(storage.download(folder, fileName), StandardCharsets.UTF_8));
      } catch (RuntimeException e) {
        log.error("No se pudo leer el informe {}: {}", id, e.getMessage());
        return Optional.empty();
      }
    });
  }

  /** De {@code sftp://host/reports/USA-1-titulo} a {@code /reports/USA-1-titulo}. */
  private static String folderOf(final String uriWithoutFile) {
    final int schemeEnd = uriWithoutFile.indexOf("://");
    if (schemeEnd < 0) {
      return uriWithoutFile;
    }
    final String afterScheme = uriWithoutFile.substring(schemeEnd + 3);
    final int firstSlash = afterScheme.indexOf('/');
    return firstSlash < 0 ? "/" : afterScheme.substring(firstSlash);
  }

  private static ReportDto toDto(final Report report) {
    return new ReportDto(report.getId(), report.getIssueKey(), report.getKind(), report.getTitle(),
        report.getFormat(), report.getSizeBytes(), report.getGeneratedAt().toString());
  }
}

package es.colorbaby.microservices.dev.relay.report;

/** Ficha de un fichero para el panel: lo justo para listarlo sin bajar el contenido. */
public record ReportDto(Long id, String issueKey, String kind, String title, String format,
    Long sizeBytes, String generatedAt) {
}

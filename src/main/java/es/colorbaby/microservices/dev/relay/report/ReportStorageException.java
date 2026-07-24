package es.colorbaby.microservices.dev.relay.report;

/** Error hablando con el FTP/SFTP de informes. */
public class ReportStorageException extends RuntimeException {

  public ReportStorageException(final String message, final Throwable cause) {
    super(message, cause);
  }
}

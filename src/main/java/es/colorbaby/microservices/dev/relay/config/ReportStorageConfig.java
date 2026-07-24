package es.colorbaby.microservices.dev.relay.config;

import es.colorbaby.microservices.dev.relay.report.FtpReportStorage;
import es.colorbaby.microservices.dev.relay.report.ReportStorage;
import es.colorbaby.microservices.dev.relay.report.SftpReportStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elige el almacén de informes según {@code maestro.reports.protocol}, igual que hace
 * {@code ms-aduana-java}. El resto del código depende de {@link ReportStorage} y no sabe si detrás
 * hay SFTP o FTP.
 */
@Slf4j
@Configuration
public class ReportStorageConfig {

  @Bean
  public ReportStorage reportStorage(final ReportStorageProperties properties) {
    final boolean sftp = !"ftp".equalsIgnoreCase(properties.getProtocol());
    log.info("Informes sobre {} en {}:{}", sftp ? "SFTP" : "FTP", properties.getHost(),
        properties.getPort());
    return sftp ? new SftpReportStorage(properties) : new FtpReportStorage(properties);
  }
}

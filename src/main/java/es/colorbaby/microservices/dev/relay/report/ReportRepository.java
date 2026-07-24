package es.colorbaby.microservices.dev.relay.report;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Índice de informes y recursos guardados en el FTP/SFTP. */
public interface ReportRepository extends JpaRepository<Report, Long> {

  /** Ficheros de una tarea, del más reciente al más antiguo. */
  List<Report> findByIssueKeyOrderByGeneratedAtDesc(String issueKey);

  /** Últimos ficheros generados, para la portada del panel. */
  List<Report> findTop50ByOrderByGeneratedAtDesc();
}

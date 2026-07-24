package es.colorbaby.microservices.dev.relay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dónde viven los informes de sixai ({@code maestro.reports}). Se reutiliza el FTP/SFTP que ya hay
 * levantado para otros proyectos; sixai no monta servidor propio.
 *
 * <p>Misma forma de configuración que {@code ms-aduana-java}, que ya trabaja con ambos protocolos:
 * se elige con {@code protocol} y el resto de propiedades son las mismas.
 */
@Data
@ConfigurationProperties(prefix = "maestro.reports")
public class ReportStorageProperties {

  /** Interruptor. Con {@code false}, sixai no toca el FTP/SFTP. */
  private boolean enabled = false;

  /** Protocolo: {@code sftp} o {@code ftp}. */
  private String protocol = "sftp";

  private String host = "localhost";

  private int port = 22;

  private String username = "";

  private String password = "";

  /** Carpeta raíz de los informes; dentro se crea una carpeta por tarea. */
  private String baseDirectory = "/reports";

  private int connectionTimeout = 10000;

  private int dataTimeout = 60000;

  /** Ruta a la clave privada para SFTP con clave (opcional). */
  private String privateKeyPath;

  /** Ruta al known_hosts para verificar el host en SFTP (opcional). */
  private String knownHostsPath;

  /** Verificación estricta del host en SFTP. */
  private boolean strictHostChecking = false;
}

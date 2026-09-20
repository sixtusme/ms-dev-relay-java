package es.colorbaby.microservices.dev.relay.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Dónde viven los informes de sixai ({@code maestro.reports}). Se reutiliza el FTP/SFTP que ya hay
 * levantado para otros proyectos; sixai no monta servidor propio.
 *
 * <p>Misma forma de configuración que {@code ms-aduana-java}, que ya trabaja con ambos protocolos:
 * se elige con {@code protocol} y el resto de propiedades son las mismas.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "maestro.reports")
public class ReportStorageProperties {

  /** Interruptor. Con {@code false}, sixai no toca el FTP/SFTP. */
  private boolean enabled = false;

  /** Protocolo: {@code sftp} o {@code ftp}. */
  @Pattern(regexp = "sftp|ftp")
  private String protocol = "sftp";

  @NotBlank
  private String host = "localhost";

  @Min(1)
  @Max(65535)
  private int port = 22;

  private String username = "";

  private String password = "";

  /** Carpeta raíz de los informes; dentro se crea una carpeta por tarea. */
  private String baseDirectory = "/reports";

  @Positive
  private int connectTimeoutMs = 10000;

  @Positive
  private int readTimeoutMs = 60000;

  /** Ruta a la clave privada para SFTP con clave (opcional). */
  private String privateKeyPath;

  /** Ruta al known_hosts para verificar el host en SFTP (opcional). */
  private String knownHostsPath;

  /** Verificación estricta del host en SFTP. */
  private boolean strictHostChecking = false;
}

package es.colorbaby.microservices.dev.relay.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Diagnóstico de despliegues fallidos ({@code maestro.deploy-diagnosis}). Cuando un despliegue se
 * cae, la consola de Jenkins casi nunca dice por qué: la causa está en el gate de Trivy (Harbor) o
 * en el contenedor que no arranca. Esto le dice a sixai dónde mirar.
 *
 * <p>El mapa de hosts refleja el inventario real de Ansible: cada entorno despliega en una máquina
 * fija ({@code fixed_target_host}).
 */
@Data
@ConfigurationProperties(prefix = "maestro.deploy-diagnosis")
public class DeployDiagnosisProperties {

  /** Interruptor. Con {@code false}, ante un fallo solo se ve la consola de Jenkins. */
  private boolean enabled = false;

  /** Si se consulta el escaneo de Harbor para explicar un bloqueo por vulnerabilidades. */
  private boolean checkHarborScan = true;

  /** Si se leen los logs del contenedor en la máquina destino. */
  private boolean readContainerLogs = true;

  /** Líneas de log del contenedor que se recogen. */
  private int logLines = 200;

  /**
   * Máquina destino por entorno, tal y como está en los roles de Ansible:
   * PRE→devops03, PROD→devops04, DINAHOSTING→dinahosting.
   */
  private Map<String, String> hosts = new HashMap<>();

  /** Patrón del nombre de contenedor; en Ansible es {@code "{{ service }}-container"}. */
  private String containerPattern = "{service}-container";

  /** Máquina de un entorno, si está inventariada. */
  public Optional<String> hostFor(final String environment) {
    if (environment == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(hosts.get(environment.toUpperCase()))
        .or(() -> Optional.ofNullable(hosts.get(environment)));
  }

  /** Nombre del contenedor de un servicio. */
  public String containerFor(final String service) {
    return containerPattern.replace("{service}", service == null ? "" : service);
  }
}

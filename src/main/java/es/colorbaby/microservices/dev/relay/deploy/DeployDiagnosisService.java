package es.colorbaby.microservices.dev.relay.deploy;

import es.colorbaby.microservices.dev.relay.config.DeployDiagnosisProperties;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.harbor.client.HarborClient;
import es.colorbaby.microservices.dev.relay.infra.client.InfraClient;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Explica por qué se cayó un despliegue, mirando la evidencia real en vez de solo la consola.
 *
 * <p>En este pipeline un despliegue falla casi siempre por una de dos cosas, y ninguna se ve en el
 * log de Jenkins:
 * <ul>
 *   <li><b>El gate de Trivy lo bloqueó</b>: hay CVE críticos en la imagen. Se consulta Harbor para
 *       poder nombrarlos, que es lo que permite actuar.</li>
 *   <li><b>El contenedor no arranca</b>: mala config, variable que falta, bucle de reinicio. Eso
 *       solo se ve en el propio host destino.</li>
 * </ul>
 *
 * <p>Con esa evidencia se pide al LLM un diagnóstico. Best-effort: si algo no se puede consultar, se
 * diagnostica con lo que haya; nunca se rompe el flujo por no poder mirar.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeployDiagnosisService {

  private static final String SYSTEM_PROMPT =
      "Eres Sixai. Te doy la evidencia de un despliegue que ha fallado: el error del job, el "
      + "escaneo de vulnerabilidades de la imagen y el estado y los logs del contenedor en la "
      + "máquina destino. Di en pocas frases, en español, la causa más probable y qué habría que "
      + "hacer. Sé concreto y no inventes: si la evidencia no basta, dilo. Distingue si es un "
      + "problema de CÓDIGO (se arregla con un cambio), de DEPENDENCIAS/seguridad (CVE que hay que "
      + "subir o revisar) o de INFRAESTRUCTURA (host, permisos, configuración del entorno).";

  private final DeployDiagnosisProperties properties;
  private final HarborClient harborClient;
  private final InfraClient infraClient;
  private final LlmClient llmClient;
  private final LlmProperties llmProperties;

  /**
   * Diagnóstico de un despliegue fallido.
   *
   * @param run    el despliegue que se cayó
   * @param reason lo que ya se sabe (error del job y consola)
   * @return el diagnóstico para añadir al aviso, o vacío si no se pudo elaborar
   */
  public String diagnose(final DeploymentRun run, final String reason) {
    if (!properties.isEnabled()) {
      return "";
    }
    final String evidence = gather(run, reason);
    if (!llmProperties.isEnabled()) {
      // Sin IA no hay razonamiento, pero la evidencia en crudo ya vale de mucho.
      return "\n\nEvidencia recogida:\n" + evidence;
    }
    try {
      final String diagnosis = llmClient.complete(
          LlmRequest.of(SYSTEM_PROMPT, evidence, LlmRoles.DIAGNOSE, run.getIssueKey()));
      return diagnosis == null || diagnosis.isBlank() ? ""
          : "\n\nDiagnóstico de Sixai:\n" + diagnosis;
    } catch (RuntimeException e) {
      log.warn("No se pudo diagnosticar el despliegue de {}: {}", run.getRepo(), e.getMessage());
      return "\n\nEvidencia recogida:\n" + evidence;
    }
  }

  private String gather(final DeploymentRun run, final String reason) {
    final StringBuilder evidence = new StringBuilder();
    evidence.append("Servicio: ").append(run.getService())
        .append("\nEntorno: ").append(run.getEnvironment())
        .append("\nVersión: ").append(run.getImageVersion() == null ? "—" : run.getImageVersion())
        .append("\n\n## Error del job\n").append(reason == null ? "—" : reason);

    appendHarborScan(run, evidence);
    appendContainer(run, evidence);
    return evidence.toString();
  }

  /** El gate de Trivy tumba despliegues sin decir qué CVE; aquí es donde se averigua. */
  private void appendHarborScan(final DeploymentRun run, final StringBuilder evidence) {
    if (!properties.isCheckHarborScan() || run.getImageVersion() == null) {
      return;
    }
    try {
      final Optional<HarborClient.ScanSummary> scan =
          harborClient.scan(run.getService(), run.getImageVersion());
      if (scan.isEmpty()) {
        return;
      }
      final HarborClient.ScanSummary summary = scan.get();
      evidence.append("\n\n## Escaneo de la imagen (Harbor)\nEstado: ")
          .append(summary.status()).append("\nVulnerabilidades: ").append(summary.counts());
      if (!summary.topCves().isEmpty()) {
        evidence.append("\nCríticas: ").append(String.join(", ", summary.topCves()));
      }
      if (summary.hasCritical()) {
        evidence.append("\n(Con críticas, el gate de seguridad BLOQUEA el despliegue.)");
      }
    } catch (RuntimeException e) {
      log.debug("No se pudo consultar Harbor: {}", e.getMessage());
    }
  }

  /** Estado y logs del contenedor en el host destino: donde se ve un arranque fallido. */
  private void appendContainer(final DeploymentRun run, final StringBuilder evidence) {
    if (!properties.isReadContainerLogs()) {
      return;
    }
    final Optional<String> host = properties.hostFor(run.getEnvironment());
    if (host.isEmpty()) {
      evidence.append("\n\n(No hay máquina inventariada para el entorno ")
          .append(run.getEnvironment()).append(".)");
      return;
    }
    final String container = properties.containerFor(run.getService());
    try {
      final String status = infraClient.containerStatus(host.get(), container);
      if (!status.isBlank()) {
        evidence.append("\n\n## Contenedor ").append(container).append(" en ").append(host.get())
            .append("\nEstado: ").append(status);
      }
      final String logs = infraClient.containerLogs(host.get(), container,
          properties.getLogLines());
      if (!logs.isBlank()) {
        evidence.append("\n\n## Logs del contenedor\n").append(logs);
      }
    } catch (RuntimeException e) {
      log.debug("No se pudo leer el contenedor {} en {}: {}", container, host.get(),
          e.getMessage());
    }
  }
}

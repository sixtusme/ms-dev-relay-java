package es.colorbaby.microservices.dev.relay.ai.agent.impl;

import es.colorbaby.microservices.dev.relay.ai.agent.Agent;
import es.colorbaby.microservices.dev.relay.ai.agent.AgentContext;
import es.colorbaby.microservices.dev.relay.ai.agent.DefaultAgentContext;
import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentResult;
import es.colorbaby.microservices.dev.relay.ai.agent.state.AgentStatus;
import es.colorbaby.microservices.dev.relay.ai.knowledge.record.KnowledgeContext;
import es.colorbaby.microservices.dev.relay.ai.skill.Skill;
import es.colorbaby.microservices.dev.relay.ai.skill.SkillRegistry;
import es.colorbaby.microservices.dev.relay.ai.tool.Tool;
import es.colorbaby.microservices.dev.relay.ai.tool.ToolRegistry;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolStatus;
import es.colorbaby.microservices.dev.relay.config.DeployDiagnosisProperties;
import es.colorbaby.microservices.dev.relay.config.DeploymentProperties;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentRun;
import es.colorbaby.microservices.dev.relay.harbor.client.HarborClient;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Explica por qué se cayó un despliegue o un build, mirando la evidencia real (Harbor, el
 * contenedor destino, la consola de Jenkins) en vez de solo el resultado del job. Reemplaza a
 * {@code DeployDiagnosisService} y al diagnóstico de consola que vivía dentro de
 * {@code DeploymentOrchestrator}: misma lógica, pero el acceso a Harbor/Infra/Jenkins es
 * exclusivamente vía tools de solo lectura, no clientes directos.
 *
 * <p>Puramente de diagnóstico: nunca corrige nada por su cuenta (la corrección automática en
 * producción está deshabilitada por diseño). Best-effort: si algo no se puede consultar, diagnostica
 * con lo que haya.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnosticianAgent implements Agent {

  public static final String ID = "diagnostician";

  private static final String SKILL_DEPLOYMENT = "diagnose-deployment";
  private static final String SKILL_CONSOLE = "diagnose-console";

  private static final String HARBOR_SCAN = "harbor.scan";
  private static final String INFRA_CONTAINER_STATUS = "infra.container_status";
  private static final String INFRA_CONTAINER_LOGS = "infra.container_logs";
  private static final String JENKINS_GET_CONSOLE = "jenkins.get_console";

  private static final String FALLBACK_DEPLOYMENT_PROMPT =
      "Eres Sixai. A partir de la evidencia de un despliegue fallido, di en pocas frases la causa "
      + "más probable y qué habría que hacer. No inventes.";
  private static final String FALLBACK_CONSOLE_PROMPT =
      "Eres Sixai. A partir de la consola de un job de Jenkins que ha fallado, resume en pocas "
      + "frases la causa más probable. No inventes.";

  private final DeployDiagnosisProperties deployDiagnosisProperties;
  private final DeploymentProperties deploymentProperties;
  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final SkillRegistry skillRegistry;
  private final ToolRegistry toolRegistry;

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String description() {
    return "Diagnostica despliegues y builds fallidos a partir de evidencia real (Harbor, "
        + "contenedor destino, consola de Jenkins). Nunca corrige, solo diagnostica.";
  }

  /**
   * Diagnóstico de un despliegue fallido.
   *
   * @param run    el despliegue que se cayó
   * @param reason lo que ya se sabe (error del job y consola)
   * @return el diagnóstico para añadir al aviso, o vacío si no se pudo elaborar
   */
  public String diagnoseDeployment(final DeploymentRun run, final String reason) {
    if (!deployDiagnosisProperties.isEnabled()) {
      return "";
    }
    final String evidence = gather(run, reason);
    if (!llmProperties.isEnabled()) {
      // Sin IA no hay razonamiento, pero la evidencia en crudo ya vale de mucho.
      return "\n\nEvidencia recogida:\n" + evidence;
    }
    try {
      final String diagnosis = llmClient.complete(LlmRequest.of(
          skillPrompt(SKILL_DEPLOYMENT, FALLBACK_DEPLOYMENT_PROMPT), evidence,
          LlmRoles.DIAGNOSE, run.getIssueKey()));
      return diagnosis == null || diagnosis.isBlank() ? ""
          : "\n\nDiagnóstico de Sixai:\n" + diagnosis;
    } catch (RuntimeException e) {
      log.warn("No se pudo diagnosticar el despliegue de {}: {}", run.getRepo(), e.getMessage());
      return "\n\nEvidencia recogida:\n" + evidence;
    }
  }

  /** Diagnóstico de la consola de un build/deploy de Jenkins que ha fallado. */
  public String diagnoseConsole(final String jobPath, final int buildNumber, final String issueKey) {
    if (!deploymentProperties.isDiagnoseOnFailure() || !llmProperties.isEnabled()) {
      return "";
    }
    try {
      final String console = invokeTool(JENKINS_GET_CONSOLE,
          Map.of("jobPath", jobPath, "buildNumber", buildNumber), issueKey)
          .map(ToolResult::content).orElse("");
      final String tail = tail(console, deploymentProperties.getConsoleMaxChars());
      final String diagnosis = llmClient.complete(LlmRequest.of(
          skillPrompt(SKILL_CONSOLE, FALLBACK_CONSOLE_PROMPT), tail, LlmRoles.DIAGNOSE, jobPath));
      return diagnosis == null || diagnosis.isBlank()
          ? "" : "\nDiagnóstico de Sixai:\n" + diagnosis;
    } catch (RuntimeException e) {
      log.warn("No se pudo diagnosticar el job {}: {}", jobPath, e.getMessage());
      return "";
    }
  }

  /** Camino genérico del {@link Agent}: diagnostica a partir de evidencia ya en texto libre. */
  @Override
  public AgentResult execute(final AgentContext context) {
    if (!llmProperties.isEnabled()) {
      return new AgentResult(AgentStatus.COMPLETED, "", List.of());
    }
    try {
      final String diagnosis = llmClient.complete(LlmRequest.of(
          skillPrompt(SKILL_DEPLOYMENT, FALLBACK_DEPLOYMENT_PROMPT),
          context.taskDescription(), LlmRoles.DIAGNOSE, context.issueKey()));
      return new AgentResult(AgentStatus.COMPLETED, diagnosis == null ? "" : diagnosis, List.of());
    } catch (RuntimeException e) {
      log.warn("No se pudo diagnosticar en {}: {}", context.issueKey(), e.getMessage());
      return new AgentResult(AgentStatus.FAILED, e.getMessage(), List.of());
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
    if (!deployDiagnosisProperties.isCheckHarborScan() || run.getImageVersion() == null) {
      return;
    }
    try {
      invokeTool(HARBOR_SCAN,
              Map.of("repository", run.getService(), "tag", run.getImageVersion()),
              run.getIssueKey())
          .map(ToolResult::data)
          .filter(HarborClient.ScanSummary.class::isInstance)
          .map(HarborClient.ScanSummary.class::cast)
          .ifPresent(summary -> {
            evidence.append("\n\n## Escaneo de la imagen (Harbor)\nEstado: ")
                .append(summary.status()).append("\nVulnerabilidades: ").append(summary.counts());
            if (!summary.topCves().isEmpty()) {
              evidence.append("\nCríticas: ").append(String.join(", ", summary.topCves()));
            }
            if (summary.hasCritical()) {
              evidence.append("\n(Con críticas, el gate de seguridad BLOQUEA el despliegue.)");
            }
          });
    } catch (RuntimeException e) {
      log.debug("No se pudo consultar Harbor: {}", e.getMessage());
    }
  }

  /** Estado y logs del contenedor en el host destino: donde se ve un arranque fallido. */
  private void appendContainer(final DeploymentRun run, final StringBuilder evidence) {
    if (!deployDiagnosisProperties.isReadContainerLogs()) {
      return;
    }
    final Optional<String> host = deployDiagnosisProperties.hostFor(run.getEnvironment());
    if (host.isEmpty()) {
      evidence.append("\n\n(No hay máquina inventariada para el entorno ")
          .append(run.getEnvironment()).append(".)");
      return;
    }
    final String container = deployDiagnosisProperties.containerFor(run.getService());
    try {
      invokeTool(INFRA_CONTAINER_STATUS, Map.of("host", host.get(), "container", container),
              run.getIssueKey())
          .map(ToolResult::content)
          .filter(status -> status != null && !status.isBlank())
          .ifPresent(status -> evidence.append("\n\n## Contenedor ").append(container)
              .append(" en ").append(host.get()).append("\nEstado: ").append(status));

      invokeTool(INFRA_CONTAINER_LOGS,
              Map.of("host", host.get(), "container", container,
                  "lines", deployDiagnosisProperties.getLogLines()),
              run.getIssueKey())
          .map(ToolResult::content)
          .filter(logs -> logs != null && !logs.isBlank())
          .ifPresent(logs -> evidence.append("\n\n## Logs del contenedor\n").append(logs));
    } catch (RuntimeException e) {
      log.debug("No se pudo leer el contenedor {} en {}: {}", container, host.get(), e.getMessage());
    }
  }

  /**
   * Este agente diagnostica de un tirón, con evidencia fija (no deja que el LLM elija qué mirar),
   * así que no necesita el bucle completo del {@code AgentRuntime}: resuelve sus tools de solo
   * lectura directamente contra el {@link ToolRegistry}.
   */
  private Optional<ToolResult> invokeTool(
      final String toolName, final Map<String, Object> arguments, final String issueKey) {
    final Tool tool = toolRegistry.find(toolName).orElse(null);
    if (tool == null) {
      log.warn("Tool no encontrada: {}", toolName);
      return Optional.empty();
    }
    final ToolResult result = tool.execute(toolContext(issueKey), new ToolArguments(arguments));
    return result.status() == ToolStatus.SUCCESS ? Optional.of(result) : Optional.empty();
  }

  private ToolContext toolContext(final String issueKey) {
    final AgentContext agentContext = new DefaultAgentContext(
        UUID.randomUUID().toString(), issueKey, "Diagnóstico", "",
        List.of(), new KnowledgeContext(List.of(), List.of()), toolRegistry.findForAgent(ID));
    return new ToolContext(agentContext.executionId(), issueKey, ID, agentContext);
  }

  private String skillPrompt(final String skillId, final String fallback) {
    return skillRegistry.find(skillId).map(Skill::instructions).orElse(fallback);
  }

  private static String tail(final String value, final int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(value.length() - max);
  }
}

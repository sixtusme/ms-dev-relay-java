package es.colorbaby.microservices.dev.relay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Compuerta de aprobación → PRE ({@code maestro.approval}). Cuando el front {@code /sixai} aprueba
 * una issue, sixai mergea sus PRs a develop y, cuando el build de develop despliega a PRE, reasigna
 * la tarea al informador y la pasa a {@code TEST}. Requiere también {@code maestro.jenkins.enabled}
 * para observar el build de develop.
 */
@Data
@ConfigurationProperties(prefix = "maestro.approval")
public class ApprovalProperties {

  /** Interruptor general. Con {@code false}, sixai no mergea ni promociona. */
  private boolean enabled = false;

  /** Simulación: loguea qué PRs mergearía, sin tocar GitHub ni Jira. */
  private boolean dryRun = true;

  /** Estado de Jira al que pasa la tarea tras desplegar a PRE (nombre EXACTO del flujo). */
  private String testStatus = "TEST";

  /** Método de merge a develop: {@code merge}, {@code squash} o {@code rebase}. */
  private String mergeMethod = "squash";
}

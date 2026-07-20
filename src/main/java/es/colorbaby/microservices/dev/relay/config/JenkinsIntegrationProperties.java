package es.colorbaby.microservices.dev.relay.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Qué hace sixai con Jenkins ({@code maestro.jenkins}). El "cómo hablar con Jenkins" (url, usuario,
 * token) vive en la lib; aquí está el comportamiento de la Fase 1: observar el build de cada PR
 * recién abierta y comentar el resultado en la tarea de Jira.
 */
@Data
@ConfigurationProperties(prefix = "maestro.jenkins")
public class JenkinsIntegrationProperties {

  /** Interruptor general. Con {@code false}, sixai no observa builds. */
  private boolean enabled = false;

  /** Simulación: en vez de sondear Jenkins, loguea qué build vigilaría (no encola nada). */
  private boolean dryRun = true;

  /**
   * Plantilla de la ruta del job en Jenkins. {@code {repo}} y {@code {branch}} se sustituyen; en
   * multibranch el nombre de rama va URL-encoded (la barra pasa a {@code %2F}), lo hace el servicio.
   */
  private String jobPathTemplate = "job/{repo}/job/{branch}";

  /** Intervalo entre barridos del observador (ms). Es el {@code fixedDelay} del programado. */
  private long pollIntervalMs = 30000;

  /** Tope de sondeos por build antes de rendirse (evita vigilar builds colgados para siempre). */
  private int maxAttempts = 40;

  /** Si al fallar el build se pide al LLM un diagnóstico de la consola. */
  private boolean diagnoseOnFailure = true;

  /** Máximo de caracteres (cola) de la consola que se mandan al LLM para diagnosticar. */
  private int consoleMaxChars = 12000;
}

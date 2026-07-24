package es.colorbaby.microservices.dev.relay.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cómo despliega sixai ({@code maestro.deploy}), calcado del pipeline real de Colorbaby: build y
 * deploy son DOS jobs de Jenkins parametrizados, no uno.
 *
 * <ul>
 *   <li>{@code back_pipeline}/{@code front_pipeline}/{@code back_library_pipeline}: compilan un
 *       REPOSITORY+BRANCH y publican la imagen en Harbor con una versión semántica.</li>
 *   <li>{@code deploy_pipeline}: despliega un SERVICE+VERSION en un ENVIRONMENT (PRE/PROD) vía
 *       Ansible.</li>
 * </ul>
 *
 * <p>El destino de producción es POR REPO: casi todos van a {@code PROD}, pero b2b2c vive en
 * Dinahosting y usa <b>otro job</b> ({@code deploy_dinahosting_pipeline}), no solo otro entorno.
 */
@Data
@ConfigurationProperties(prefix = "maestro.deploy")
public class DeploymentProperties {

  /** Interruptor general. Con {@code false}, sixai no compila ni despliega nada. */
  private boolean enabled = false;

  /** Simulación: loguea qué jobs lanzaría, sin tocar Jenkins. */
  private boolean dryRun = true;

  /** Job de despliegue por defecto. */
  private String deployJob = "job/deploy_pipeline";

  /** Job de build por tipo de pipeline: {@code back}, {@code front}, {@code library}. */
  private Map<String, String> buildJobs = new HashMap<>();

  /** Intervalo del orquestador (ms). Es el {@code fixedDelay} del barrido. */
  private long pollIntervalMs = 30000;

  /** Tope de sondeos por despliegue antes de rendirse (build + deploy pueden tardar). */
  private int maxAttempts = 60;

  /** Si al fallar un job se pide al LLM un diagnóstico de la consola. */
  private boolean diagnoseOnFailure = true;

  /** Máximo de caracteres (cola) de la consola que se mandan al LLM para diagnosticar. */
  private int consoleMaxChars = 12000;

  /** Repos desplegables. Un repo que no esté aquí, sixai no lo despliega (y lo dice). */
  private List<Repo> repos = List.of();

  /** Configuración de despliegue de un repo. */
  @Data
  public static class Repo {

    /** Nombre del repo en GitHub. */
    private String name;

    /** Tipo de pipeline de build: {@code back}, {@code front} o {@code library}. */
    private String pipeline = "back";

    /** Nombre del servicio en Harbor/Ansible. Si se omite, se usa {@code name}. */
    private String service;

    /** Entorno de preproducción. */
    private String preEnvironment = "PRE";

    /** Entorno de producción (para b2b2c es {@code DINAHOSTING}). */
    private String prodEnvironment = "PROD";

    /** Job de deploy de producción si NO es el por defecto (b2b2c: dinahosting). */
    private String prodJob;

    /** Nombre efectivo del servicio. */
    public String serviceName() {
      return service == null || service.isBlank() ? name : service;
    }
  }

  /** Configuración de un repo, si es desplegable. */
  public Optional<Repo> findRepo(final String name) {
    return repos.stream().filter(r -> r.getName() != null && r.getName().equals(name)).findFirst();
  }

  /** Job de build para un tipo de pipeline. */
  public Optional<String> buildJob(final String pipeline) {
    return Optional.ofNullable(buildJobs.get(pipeline));
  }
}

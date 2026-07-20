package es.colorbaby.microservices.dev.relay.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Qué hace sixai con GitHub al poner una tarea en curso ({@code maestro.github}). El "cómo hablar
 * con GitHub" (org, token, url) vive en la lib; aquí está el comportamiento.
 */
@Data
@ConfigurationProperties(prefix = "maestro.github")
public class GithubIntegrationProperties {

  /** Interruptor general. Con {@code false}, sixai no toca GitHub. */
  private boolean enabled = false;

  /** Simulación: loguea las PRs que abriría, sin crear ramas ni PRs. */
  private boolean dryRun = true;

  /** Prefijo de las ramas que crea sixai (ej. {@code sixai/USA-938-1699999999}). */
  private String branchPrefix = "sixai/";

  /**
   * Rama base de las PRs: sixai ramifica desde ella y la PR la apunta a ella. Es {@code develop}
   * (existe en todos los repos). La rama por defecto (main/master) se reserva para PROD.
   */
  private String baseBranch = "develop";

  /**
   * Mapeo de sistema a repos candidatos. Se recorre en orden; el primer sistema cuya palabra clave
   * aparezca en el nombre de la épica, el título, la clave de la issue o las labels aporta sus repos
   * candidatos. Cuál de esos candidatos hay que tocar de verdad lo decide {@code RepoSelector}.
   */
  private List<Project> projects = List.of();

  /** Un sistema y sus repos candidatos. */
  @Data
  public static class Project {

    /** Palabras clave (épica/título/proyecto/label) que identifican este sistema. */
    private List<String> keywords = List.of();

    /** Repos candidatos del sistema, cada uno con su rol y keywords de afinado. */
    private List<Repo> repos = List.of();
  }

  /** Un repo candidato: nombre, rol (pista para el LLM/humanos) y keywords para el fallback. */
  @Data
  public static class Repo {

    /** Nombre del repo (sin la org). */
    private String name;

    /** Rol del repo; pista para que el LLM y las personas sepan qué contiene (ej. "frontend/UI"). */
    private String role = "";

    /** Palabras clave para el fallback determinista cuando la IA está apagada. */
    private List<String> keywords = List.of();
  }
}

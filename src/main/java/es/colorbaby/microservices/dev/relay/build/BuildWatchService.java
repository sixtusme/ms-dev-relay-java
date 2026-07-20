package es.colorbaby.microservices.dev.relay.build;

import es.colorbaby.microservices.dev.relay.config.JenkinsIntegrationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Punto de entrada para "vigila el build de esta rama". Se llama tras aterrizar el código en la
 * rama: el merge a {@code develop} (build para PRE) o a {@code main}/{@code master} (build para
 * PROD) — no al abrir la PR. Decide si procede (según {@code maestro.jenkins.enabled} y
 * {@code dry-run}), calcula la ruta del job y encola el watch para que {@link JenkinsBuildObserver}
 * lo sondee.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildWatchService {

  private final JenkinsIntegrationProperties properties;
  private final BuildWatchRegistry registry;

  /** Registra, si procede, la vigilancia del build de una rama tras el merge. Best-effort. */
  public void watch(final String issueKey, final String repo, final String branch) {
    if (!properties.isEnabled()) {
      return;
    }
    final String jobPath = jobPath(repo, branch);
    if (properties.isDryRun()) {
      log.info("[DRY-RUN] Observaría el build de {} (job {})", repo, jobPath);
      return;
    }
    registry.add(new BuildWatch(issueKey, repo, branch, jobPath));
    log.info("Vigilando el build de {} para {} (job {})", repo, issueKey, jobPath);
  }

  private String jobPath(final String repo, final String branch) {
    final String encodedBranch = branch.replace("/", "%2F");
    return properties.getJobPathTemplate()
        .replace("{repo}", repo)
        .replace("{branch}", encodedBranch);
  }
}

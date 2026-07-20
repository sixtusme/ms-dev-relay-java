package es.colorbaby.microservices.dev.relay.pullrequest;

import es.colorbaby.microservices.dev.relay.coder.CoderService;
import es.colorbaby.microservices.dev.relay.config.GithubIntegrationProperties;
import es.colorbaby.microservices.dev.relay.github.client.GithubClient;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.jira.config.JiraProperties;
import es.colorbaby.microservices.dev.relay.jira.util.JiraTextExtractor;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFields;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Al poner una tarea en curso, arranca el trabajo en GitHub. {@link RepoResolver} da los repos
 * candidatos del sistema y {@link RepoSelector} acota a los que realmente hay que tocar; por cada
 * uno crea una rama {@code sixai/<ISSUE>-<ts>} desde {@code develop}, deja un commit de arranque y
 * abre una draft-PR hacia {@code develop}. Luego comenta los enlaces en la propia tarea de Jira.
 *
 * <p>Todo depende de {@code maestro.github.enabled}; con {@code dry-run} loguea lo que haría sin
 * tocar GitHub. El code-gen real (LLM) rellenará la PR más adelante. La observación del build es
 * posterior: arranca tras aprobar y mergear la PR a {@code develop} (Fase 3), no al abrirla.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PullRequestService {

  private final GithubClient githubClient;
  private final JiraClient jiraClient;
  private final RepoResolver repoResolver;
  private final RepoSelector repoSelector;
  private final CoderService coderService;
  private final GithubIntegrationProperties properties;
  private final JiraProperties jiraProperties;

  /** Abre las PRs de una issue ya puesta en curso. Best-effort: nunca relanza. */
  public void openForIssue(final String issueKey) {
    if (!properties.isEnabled()) {
      return;
    }
    try {
      JiraIssueDto issue = jiraClient.getIssue(issueKey);
      if (issue == null) {
        return;
      }
      List<GithubIntegrationProperties.Repo> candidates = repoResolver.resolveCandidates(issue);
      if (candidates.isEmpty()) {
        log.info("Sin repos mapeados para {} (revisa maestro.github.projects)", issueKey);
        return;
      }
      List<String> repos = repoSelector.select(issue, candidates);
      if (repos.isEmpty()) {
        log.info("Ningún repo seleccionado para {} entre los candidatos", issueKey);
        return;
      }

      JiraIssueDtoFields fields = issue.getFields();
      String summary = fields == null || fields.getSummary() == null ? issueKey : fields.getSummary();
      String description = fields == null
          ? "" : JiraTextExtractor.extractPlainText(fields.getDescription());

      openPullRequests(issueKey, summary, description, repos);
    } catch (RuntimeException e) {
      log.error("Error abriendo PRs para {}: {}", issueKey, e.getMessage());
    }
  }

  private void openPullRequests(
      final String issueKey, final String summary, final String description,
      final List<String> repos) {

    String title = "sixai · " + issueKey + " · " + summary;
    String body = prBody(issueKey, summary, description);
    String placeholder = placeholderFile(issueKey, summary, description);

    List<String> links = new ArrayList<>();
    for (String repo : repos) {
      String branch = properties.getBranchPrefix() + issueKey + "-" + Instant.now().getEpochSecond();

      if (properties.isDryRun()) {
        log.info("[DRY-RUN] Abriría draft-PR en {} (rama {})", repo, branch);
        continue;
      }
      try {
        final String base = properties.getBaseBranch();
        githubClient.createBranch(repo, branch, githubClient.getBranchSha(repo, base));
        // El coder intenta implementar la tarea; si no puede (apagado/dry-run/sin cambios/fallo),
        // se deja el placeholder para que la PR tenga al menos un commit que la sostenga.
        final boolean coded =
            coderService.tryImplement(issueKey, repo, branch, summary, description);
        if (!coded) {
          githubClient.putFile(repo, branch, ".sixai/" + issueKey + ".md",
              placeholder, "chore(sixai): arranque de " + issueKey);
        }
        GithubClient.PullRequest pr = githubClient.createPullRequest(
            repo, branch, base, title, body, true);
        log.info("Draft-PR abierta en {} hacia {}: {}", repo, base, pr.url());
        links.add("- " + repo + " #" + pr.number() + ": " + pr.url());
        // La observación del build NO arranca aquí: empieza tras aprobar y mergear la PR a develop
        // (Fase 3), que llamará a BuildWatchService.watch(issueKey, repo, develop).
      } catch (RuntimeException e) {
        log.error("No se pudo abrir la PR en {} para {}: {}", repo, issueKey, e.getMessage());
      }
    }

    if (!links.isEmpty()) {
      jiraClient.addComment(issueKey,
          "sixai ha arrancado el trabajo abriendo estas PRs:\n" + String.join("\n", links));
    }
  }

  private String prBody(final String issueKey, final String summary, final String description) {
    return "## " + summary + "\n\n"
        + (description == null || description.isBlank() ? "_(sin descripción)_" : description)
        + "\n\n---\nPR de arranque creada por **sixai** para " + browseUrl(issueKey)
        + ".\nEl contenido llegará cuando el agente procese la tarea.";
  }

  private String placeholderFile(
      final String issueKey, final String summary, final String description) {
    return "# sixai · " + issueKey + "\n\n**" + summary + "**\n\n"
        + (description == null || description.isBlank() ? "(sin descripción)" : description)
        + "\n\n---\nTarea Jira: " + browseUrl(issueKey)
        + "\nRama de trabajo creada por sixai; a la espera del agente.\n";
  }

  private String browseUrl(final String issueKey) {
    String base = jiraProperties.getBaseUrl();
    return (base == null ? "" : base) + "/browse/" + issueKey;
  }
}

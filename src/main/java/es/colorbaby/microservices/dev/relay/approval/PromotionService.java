package es.colorbaby.microservices.dev.relay.approval;

import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.config.GithubIntegrationProperties;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentCoordinator;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentPhase;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentService;
import es.colorbaby.microservices.dev.relay.github.client.GithubClient;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.pullrequest.RepoResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Promoción a producción: por cada repo que tocó la tarea, mergea {@code develop} en la rama por
 * defecto ({@code main} o {@code master}, según el repo) y lanza el despliegue a PROD. Tras el
 * comando {@code /sixai PROD} autorizado no hay más aprobaciones: esa orden ES la compuerta.
 *
 * <p>Para saber qué repos tocó, mira las PRs de sixai <b>ya mergeadas</b> hacia develop: en este
 * punto están cerradas, así que buscarlas abiertas no valdría. Al terminar, el
 * {@link DeploymentCoordinator} comenta que está en producción y <b>deja la tarea en TEST</b> para
 * que el cliente lo verifique allí.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromotionService {

  private final GithubClient githubClient;
  private final JiraClient jiraClient;
  private final RepoResolver repoResolver;
  private final DeploymentService deploymentService;
  private final TaskRecorder taskRecorder;
  private final GithubIntegrationProperties githubProperties;

  /** Promociona a producción los repos de una issue. Best-effort: nunca relanza. */
  public void promote(final String issueKey, final JiraIssueDto issue) {
    try {
      final Map<String, String> mainByRepo = findTouchedRepos(issueKey, issue);
      if (mainByRepo.isEmpty()) {
        jiraClient.addComment(issueKey, "No encuentro PRs de sixai mergeadas para esta tarea, "
            + "así que no hay nada que promocionar a producción.");
        return;
      }
      final String develop = githubProperties.getBaseBranch();
      final String message = "chore(sixai): promoción de " + issueKey + " a producción";
      final Map<String, String> merged = new LinkedHashMap<>();
      for (final Map.Entry<String, String> entry : mainByRepo.entrySet()) {
        final String repo = entry.getKey();
        final String main = entry.getValue();
        try {
          githubClient.mergeBranches(repo, main, develop, message);
          log.info("{} mergeada en {} de {} para {}", develop, main, repo, issueKey);
          taskRecorder.record(issueKey, TaskEventType.PROMOTED, "sixai",
              repo + ": " + develop + " → " + main);
          merged.put(repo, main);
        } catch (RuntimeException e) {
          log.error("No se pudo promocionar {} para {}: {}", repo, issueKey, e.getMessage());
          jiraClient.addComment(issueKey, "⚠️ No se pudo mergear " + develop + " en " + main
              + " de " + repo + ": " + e.getMessage());
        }
      }
      if (merged.isEmpty()) {
        return;
      }
      // El lote se crea con lo que realmente arranca; lo que no, se dice en la tarea.
      final DeploymentService.StartResult result =
          deploymentService.startBatch(issueKey, DeploymentPhase.PROD, null, merged);
      if (!result.skipped().isEmpty()) {
        jiraClient.addComment(issueKey, "ℹ️ Estos repos no se despliegan por el pipeline y quedan "
            + "fuera de la promoción: " + String.join(", ", result.skipped()) + ".");
      }
    } catch (RuntimeException e) {
      log.error("Error promocionando {}: {}", issueKey, e.getMessage());
    }
  }

  /** Repos con PR de sixai ya mergeada hacia develop → su rama por defecto (main o master). */
  private Map<String, String> findTouchedRepos(final String issueKey, final JiraIssueDto issue) {
    final String base = githubProperties.getBaseBranch();
    final String headPrefix = githubProperties.getBranchPrefix() + issueKey + "-";
    final Map<String, String> result = new LinkedHashMap<>();
    for (final GithubIntegrationProperties.Repo repo : repoResolver.resolveCandidates(issue)) {
      final var prs = githubClient.listPullRequests(repo.getName(), base, "closed");
      for (final GithubClient.PullRequest pr : prs) {
        if (pr.head() != null && pr.head().startsWith(headPrefix)) {
          result.put(repo.getName(), githubClient.getDefaultBranch(repo.getName()).name());
          break;
        }
      }
    }
    return result;
  }
}

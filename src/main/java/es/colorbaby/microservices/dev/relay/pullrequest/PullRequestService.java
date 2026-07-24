package es.colorbaby.microservices.dev.relay.pullrequest;

import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.coder.CoderService;
import es.colorbaby.microservices.dev.relay.config.GithubIntegrationProperties;
import es.colorbaby.microservices.dev.relay.github.client.GithubClient;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.jira.config.JiraProperties;
import es.colorbaby.microservices.dev.relay.jira.util.JiraTextExtractor;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFields;
import es.colorbaby.microservices.dev.relay.report.ReportService;
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
  private final TaskRecorder taskRecorder;
  private final ReportService reportService;
  private final GithubIntegrationProperties properties;
  private final JiraProperties jiraProperties;

  /** Abre las PRs de una issue ya puesta en curso. Best-effort: nunca relanza. */
  public void openForIssue(final String issueKey) {
    openForIssue(issueKey, null);
  }

  /**
   * Abre las PRs de una issue. Con {@code correction} se trata de un ciclo de corrección: se
   * abren PRs NUEVAS (las anteriores ya se mergearon a develop) con lo que hay que arreglar.
   *
   * @param correction qué hay que corregir, o null si es el primer arranque
   */
  public void openForIssue(final String issueKey, final String correction) {
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

      // Aquí ya se ha leído la issue de Jira: se aprovecha para completar el registro sin gastar
      // otra llamada a la API.
      taskRecorder.describe(issueKey, summary, epicOf(fields), systemOf(candidates));

      if (correction == null) {
        // Solo en el primer arranque: se crea la carpeta y se copian los adjuntos de quien creó la
        // tarea. En una corrección la carpeta ya existe y volver a copiarlos sería ruido.
        reportService.prepareFolder(issue);
      }

      // En una corrección, al coder se le da la descripción original MÁS lo que hay que arreglar:
      // sin el original perdería el contexto de qué se pedía.
      final String brief = correction == null ? description
          : description + "\n\n## Corrección solicitada\n" + correction;

      final List<String> coded = openPullRequests(issueKey, summary, brief, repos, correction);

      // El informe se publica cuando el coder ya ha resuelto: antes no habría nada que contar.
      if (!coded.isEmpty()) {
        reportService.publishReport(issue,
            deliveryReport(issueKey, summary, brief, coded));
      }
    } catch (RuntimeException e) {
      log.error("Error abriendo PRs para {}: {}", issueKey, e.getMessage());
    }
  }

  /**
   * Abre las PRs y devuelve las líneas de resumen de aquellas en las que el coder SÍ escribió
   * código: son las que dan contenido al informe (si no hay ninguna, no hay nada que informar).
   */
  private List<String> openPullRequests(
      final String issueKey, final String summary, final String description,
      final List<String> repos, final String correction) {

    String title = (correction == null ? "sixai · " : "sixai (corrección) · ")
        + issueKey + " · " + summary;
    String body = prBody(issueKey, summary, description);
    String placeholder = placeholderFile(issueKey, summary, description);

    List<String> codedRepos = new ArrayList<>();
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
        taskRecorder.record(issueKey, TaskEventType.PR_OPENED, "sixai",
            repo + " #" + pr.number() + " (" + branch + " → " + base + "): " + pr.url());
        if (coded) {
          taskRecorder.record(issueKey, TaskEventType.CODE_GENERATED, "sixai", repo);
          codedRepos.add("- **" + repo + "** — PR [#" + pr.number() + "](" + pr.url() + "), rama `"
              + branch + "` → `" + base + "`");
        }
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
    return codedRepos;
  }

  /** El informe que se publica en la carpeta de la tarea cuando el coder ha resuelto algo. */
  private String deliveryReport(final String issueKey, final String summary,
      final String description, final List<String> codedRepos) {
    return "# " + issueKey + " — " + summary + "\n\n"
        + "## Qué se pedía\n\n"
        + (description == null || description.isBlank() ? "_(sin descripción)_" : description)
        + "\n\n## Qué ha hecho sixai\n\n"
        + String.join("\n", codedRepos)
        + "\n\n> Código generado por **sixai** y pendiente de aprobación humana antes de "
        + "mergear a `" + properties.getBaseBranch() + "`.\n\n"
        + "Tarea: " + browseUrl(issueKey) + "\n";
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

  /** Nombre de la épica, que es como se identifica el trabajo mayor al que pertenece la tarea. */
  private static String epicOf(final JiraIssueDtoFields fields) {
    if (fields == null || fields.getParent() == null || fields.getParent().getFields() == null) {
      return null;
    }
    return fields.getParent().getFields().getSummary();
  }

  /** Sistema al que pertenece, deducido del primer repo candidato (pim, docs, b2b2c…). */
  private static String systemOf(final List<GithubIntegrationProperties.Repo> candidates) {
    return candidates.isEmpty() ? null : candidates.get(0).getName();
  }

  private String browseUrl(final String issueKey) {
    String base = jiraProperties.getBaseUrl();
    return (base == null ? "" : base) + "/browse/" + issueKey;
  }
}

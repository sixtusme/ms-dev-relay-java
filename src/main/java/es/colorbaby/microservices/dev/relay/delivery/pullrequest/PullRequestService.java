package es.colorbaby.microservices.dev.relay.delivery.pullrequest;

import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.ai.agent.impl.CoderAgent;
import es.colorbaby.microservices.dev.relay.ai.agent.impl.PlannerAgent;
import es.colorbaby.microservices.dev.relay.ai.agent.impl.ReviewerAgent;
import es.colorbaby.microservices.dev.relay.ai.agent.impl.SelectorAgent;
import es.colorbaby.microservices.dev.relay.ai.agent.state.AgentStatus;
import es.colorbaby.microservices.dev.relay.ai.orchestration.AgentRuntime;
import es.colorbaby.microservices.dev.relay.ai.orchestration.record.AgentExecutionRequest;
import es.colorbaby.microservices.dev.relay.ai.orchestration.record.AgentExecutionResult;
import es.colorbaby.microservices.dev.relay.config.GithubIntegrationProperties;
import es.colorbaby.microservices.dev.relay.delivery.verification.VerificationService;
import es.colorbaby.microservices.dev.relay.github.client.GithubClient;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.jira.config.JiraProperties;
import es.colorbaby.microservices.dev.relay.jira.util.JiraTextExtractor;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFields;
import es.colorbaby.microservices.dev.relay.panel.report.ReportService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Al poner una tarea en curso, arranca el trabajo en GitHub. {@link RepoResolver} da los repos
 * candidatos del sistema y {@link SelectorAgent} acota a los que realmente hay que tocar; por cada
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
  private final SelectorAgent selectorAgent;
  private final AgentRuntime agentRuntime;
  private final TaskRecorder taskRecorder;
  private final ReportService reportService;
  private final VerificationService verificationService;
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
      List<String> repos = selectorAgent.select(issue, candidates);
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
        // El planner decide el enfoque ANTES de que el coder escriba nada (solo lectura); el coder
        // lo sigue, pero decide por su cuenta si el planner está apagado o no dejó plan.
        final String plan = planWithAgent(issueKey, repo, branch, summary, description);
        // El coder intenta implementar la tarea; si no puede (apagado/dry-run/sin cambios/fallo),
        // se deja el placeholder para que la PR tenga al menos un commit que la sostenga.
        final AgentExecutionResult coderResult =
            codeWithAgent(issueKey, repo, branch, summary, description, plan);
        final boolean coded = coderResult.status() == AgentStatus.COMPLETED
            && coderResult.summary() != null && !coderResult.summary().isBlank();
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
          // El reviewer solo lee lo que el coder ya commiteó; su veredicto es una opinión más
          // para quien apruebe, nunca un bloqueo.
          reviewWithAgent(issueKey, repo, branch, summary, description, coderResult, pr.number());
        }
        // Se manda compilar la rama YA, para que cuando alguien mire la PR sepa si se sostiene.
        // El veredicto tarda unos minutos y llega solo, a la tarea y al panel.
        verificationService.verify(issueKey, repo, branch, pr.number());
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

  /**
   * Pide al {@link PlannerAgent}, vía {@link AgentRuntime}, un plan de implementación (solo
   * lectura). Vacío si está apagado, no dejó plan o falló — el coder decide por su cuenta en ese
   * caso, igual que antes de que existiera el planner.
   */
  private String planWithAgent(final String issueKey, final String repo, final String branch,
      final String summary, final String description) {
    final AgentExecutionResult result = agentRuntime.execute(new AgentExecutionRequest(
        issueKey, summary, description, PlannerAgent.ID,
        Map.of("repo", repo, "branch", branch)));
    return result.status() == AgentStatus.COMPLETED && result.summary() != null
        ? result.summary() : "";
  }

  /**
   * Pide al {@link CoderAgent}, vía {@link AgentRuntime}, que implemente la tarea en la rama.
   * Devuelve el resultado completo (no solo si commiteó) porque lleva en {@code data()} las rutas
   * cambiadas, que necesita el reviewer.
   */
  private AgentExecutionResult codeWithAgent(final String issueKey, final String repo,
      final String branch, final String summary, final String description, final String plan) {
    return agentRuntime.execute(new AgentExecutionRequest(
        issueKey, summary, description, CoderAgent.ID,
        Map.of("repo", repo, "branch", branch, "plan", plan)));
  }

  /**
   * Pide al {@link ReviewerAgent}, vía {@link AgentRuntime}, un veredicto sobre lo que el coder
   * acaba de commitear, y lo comenta en la tarea junto al número de la PR. Best-effort: un fallo
   * aquí no afecta a la PR, que ya está abierta.
   */
  @SuppressWarnings("unchecked")
  private void reviewWithAgent(final String issueKey, final String repo, final String branch,
      final String summary, final String description, final AgentExecutionResult coderResult,
      final int prNumber) {
    final Object changedFiles = coderResult.data().get("changedFiles");
    if (!(changedFiles instanceof List<?> paths) || paths.isEmpty()) {
      return;
    }
    try {
      final String codeSummary = coderResult.summary() == null ? "" : coderResult.summary();
      final AgentExecutionResult result = agentRuntime.execute(new AgentExecutionRequest(
          issueKey, summary, description, ReviewerAgent.ID,
          Map.of("repo", repo, "branch", branch, "changedFiles", (List<String>) paths,
              "codeSummary", codeSummary)));
      if (result.status() == AgentStatus.COMPLETED && result.summary() != null
          && !result.summary().isBlank()) {
        jiraClient.addComment(issueKey, "🔍 Revisión automática de " + repo + " (PR #" + prNumber
            + "):\n\n" + result.summary());
      }
    } catch (RuntimeException e) {
      log.warn("El reviewer falló en {} para {}: {}", repo, issueKey, e.getMessage());
    }
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

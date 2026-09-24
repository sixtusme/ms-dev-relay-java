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
import es.colorbaby.microservices.dev.relay.control.lifecycle.Evidence;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceKind;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseOutcome;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Recommendation;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskPhase;
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
  public List<PhaseOutcome> openForIssue(final String issueKey) {
    return openForIssue(issueKey, null);
  }

  /**
   * Abre las PRs de una issue. Con {@code correction} se trata de un ciclo de corrección: se
   * abren PRs NUEVAS (las anteriores ya se mergearon a develop) con lo que hay que arreglar.
   *
   * @param correction qué hay que corregir, o null si es el primer arranque
   * @return resultados de REPO_SELECTION e IMPLEMENTATION, en el orden en que hay que
   *     entregárselos al ciclo de vida. Vacío si la integración con GitHub está apagada: no se ha
   *     hecho nada, así que no hay nada que contar.
   */
  public List<PhaseOutcome> openForIssue(final String issueKey, final String correction) {
    if (!properties.isEnabled()) {
      return List.of();
    }
    final List<PhaseOutcome> outcomes = new ArrayList<>();
    try {
      JiraIssueDto issue = jiraClient.getIssue(issueKey);
      if (issue == null) {
        outcomes.add(selectionFailed("no se pudo leer la tarea de Jira"));
        return outcomes;
      }
      List<GithubIntegrationProperties.Repo> candidates = repoResolver.resolveCandidates(issue);
      if (candidates.isEmpty()) {
        log.info("Sin repos mapeados para {} (revisa maestro.github.projects)", issueKey);
        outcomes.add(selectionFailed(
            "sin repos mapeados para su sistema (revisa maestro.github.projects)"));
        return outcomes;
      }
      final SelectorAgent.Selection selection = selectorAgent.select(issue, candidates);
      final List<String> repos = selection.repos();
      if (repos.isEmpty()) {
        log.info("Ningún repo seleccionado para {} entre los candidatos", issueKey);
        outcomes.add(selectionFailed("ningún repo seleccionado entre los candidatos"));
        return outcomes;
      }
      outcomes.add(PhaseOutcome.of(TaskPhase.REPO_SELECTION, Recommendation.PASS,
          Evidence.of(EvidenceKind.DECISION, "Repos elegidos (" + selection.method() + "): "
              + String.join(", ", repos))));

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

      final List<String> coded =
          openPullRequests(issueKey, summary, brief, repos, correction, outcomes);

      // El informe se publica cuando el coder ya ha resuelto: antes no habría nada que contar.
      if (!coded.isEmpty()) {
        reportService.publishReport(issue,
            deliveryReport(issueKey, summary, brief, coded));
      }
    } catch (RuntimeException e) {
      log.error("Error abriendo PRs para {}: {}", issueKey, e.getMessage());
      // Si aún no se habían elegido repos, lo que falló fue la selección; si no, la implementación.
      // Si la implementación ya tiene veredicto, el fallo es posterior (comentario, informe) y no
      // lo cambia.
      final String reason = "error abriendo PRs: " + e.getMessage();
      final boolean implementationClosed = outcomes.stream()
          .anyMatch(o -> o.phase() == TaskPhase.IMPLEMENTATION && o.isAggregate());
      if (outcomes.isEmpty()) {
        outcomes.add(selectionFailed(reason));
      } else if (!implementationClosed) {
        outcomes.add(PhaseOutcome.of(TaskPhase.IMPLEMENTATION, Recommendation.FAIL,
            Evidence.of(EvidenceKind.REASON, reason)));
      }
    }
    return outcomes;
  }

  /**
   * Abre las PRs y devuelve las líneas de resumen de aquellas en las que el coder SÍ escribió
   * código: son las que dan contenido al informe (si no hay ninguna, no hay nada que informar).
   * Deja en {@code outcomes} un resultado de IMPLEMENTATION por repo y, al final, el agregado.
   */
  private List<String> openPullRequests(
      final String issueKey, final String summary, final String description,
      final List<String> repos, final String correction, final List<PhaseOutcome> outcomes) {

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
        outcomes.add(PhaseOutcome.forRepo(TaskPhase.IMPLEMENTATION, Recommendation.SKIPPED, repo,
            Evidence.of(EvidenceKind.REASON, "dry-run: no se abre PR")));
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

        // La PR cuenta como evidencia aunque sea de placeholder: la verificación la compila igual.
        final List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence(EvidenceKind.PR,
            "#" + pr.number() + " (" + branch + " → " + base + ")", pr.url()));
        if (!plan.isBlank()) {
          evidence.add(Evidence.of(EvidenceKind.PLAN, plan));
        }
        if (coded) {
          taskRecorder.record(issueKey, TaskEventType.CODE_GENERATED, "sixai", repo);
          codedRepos.add("- **" + repo + "** — PR [#" + pr.number() + "](" + pr.url() + "), rama `"
              + branch + "` → `" + base + "`");
          // El reviewer solo lee lo que el coder ya commiteó; su veredicto es una opinión más
          // para quien apruebe, nunca un bloqueo.
          final String review =
              reviewWithAgent(issueKey, repo, branch, summary, description, coderResult, pr.number());
          if (review != null) {
            evidence.add(Evidence.of(EvidenceKind.REVIEW, review));
          }
        } else {
          evidence.add(Evidence.of(EvidenceKind.REASON,
              "sin código del coder: " + coderOutcome(coderResult)));
        }
        outcomes.add(new PhaseOutcome(TaskPhase.IMPLEMENTATION,
            coded ? Recommendation.PASS : Recommendation.FAIL, repo, evidence, PhaseOutcome.SIXAI));

        // Se manda compilar la rama YA, para que cuando alguien mire la PR sepa si se sostiene.
        // El veredicto tarda unos minutos y llega solo, a la tarea y al panel.
        verificationService.verify(issueKey, repo, branch, pr.number());
      } catch (RuntimeException e) {
        log.error("No se pudo abrir la PR en {} para {}: {}", repo, issueKey, e.getMessage());
        outcomes.add(PhaseOutcome.forRepo(TaskPhase.IMPLEMENTATION, Recommendation.FAIL, repo,
            Evidence.of(EvidenceKind.REASON, "no se pudo abrir la PR: " + e.getMessage())));
      }
    }

    // El veredicto va ANTES del comentario: si Jira falla al comentar, las PRs y el código ya
    // existen, y la implementación no debe quedar como fallida por eso.
    outcomes.add(implementationVerdict(repos.size(), codedRepos.size(), properties.isDryRun()));
    if (!links.isEmpty()) {
      jiraClient.addComment(issueKey,
          "sixai ha arrancado el trabajo abriendo estas PRs:\n" + String.join("\n", links));
    }
    return codedRepos;
  }

  /**
   * Veredicto de IMPLEMENTATION: PASS si algún repo tiene código de verdad; SKIPPED si todo fue
   * simulación; FAIL si no (todo placeholder o ninguna PR abierta).
   */
  private static PhaseOutcome implementationVerdict(final int repos, final int coded,
      final boolean dryRun) {
    final Recommendation recommendation;
    if (coded > 0) {
      recommendation = Recommendation.PASS;
    } else {
      recommendation = dryRun ? Recommendation.SKIPPED : Recommendation.FAIL;
    }
    return PhaseOutcome.of(TaskPhase.IMPLEMENTATION, recommendation,
        Evidence.of(EvidenceKind.REASON, coded + " de " + repos + " repo(s) con código del coder"
            + (dryRun ? " (dry-run)" : "")));
  }

  /** Por qué el coder no dejó código, tal y como lo cuenta él (ver {@link CoderAgent.Outcome}). */
  private static String coderOutcome(final AgentExecutionResult result) {
    if (result.status() != AgentStatus.COMPLETED) {
      final String summary = result.summary();
      return result.status() + (summary == null || summary.isBlank() ? "" : " — " + summary);
    }
    final Object outcome = result.data().get(CoderAgent.DATA_OUTCOME);
    return outcome == null ? "motivo desconocido" : String.valueOf(outcome);
  }

  private static PhaseOutcome selectionFailed(final String reason) {
    return PhaseOutcome.of(TaskPhase.REPO_SELECTION, Recommendation.FAIL,
        Evidence.of(EvidenceKind.REASON, reason));
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
   *
   * @return el veredicto, o null si no hubo (sin cambios que revisar, reviewer apagado o fallo)
   */
  @SuppressWarnings("unchecked")
  private String reviewWithAgent(final String issueKey, final String repo, final String branch,
      final String summary, final String description, final AgentExecutionResult coderResult,
      final int prNumber) {
    final Object changedFiles = coderResult.data().get("changedFiles");
    if (!(changedFiles instanceof List<?> paths) || paths.isEmpty()) {
      return null;
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
        return result.summary();
      }
    } catch (RuntimeException e) {
      log.warn("El reviewer falló en {} para {}: {}", repo, issueKey, e.getMessage());
    }
    return null;
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

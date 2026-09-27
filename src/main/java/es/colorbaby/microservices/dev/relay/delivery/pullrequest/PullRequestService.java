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
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceSource;
import es.colorbaby.microservices.dev.relay.control.lifecycle.FailureReason;
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
import java.util.Optional;
import java.util.function.Consumer;
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
  private final PlannerAgent plannerAgent;
  private final AgentRuntime agentRuntime;
  private final TaskRecorder taskRecorder;
  private final ReportService reportService;
  private final VerificationService verificationService;
  private final GithubIntegrationProperties properties;
  private final JiraProperties jiraProperties;

  /** Abre las PRs de una issue ya puesta en curso. Best-effort: nunca relanza. */
  public void openForIssue(final String issueKey, final Consumer<PhaseOutcome> onOutcome) {
    openForIssue(issueKey, null, onOutcome);
  }

  /**
   * Abre las PRs de una issue. Con {@code correction} se trata de un ciclo de corrección: se
   * abren PRs NUEVAS (las anteriores ya se mergearon a develop) con lo que hay que arreglar.
   *
   * <p>Cada resultado de REPO_SELECTION e IMPLEMENTATION se entrega a {@code onOutcome} EN CUANTO
   * se produce, no al final: el barrido de verificación ({@code VerificationOrchestrator}) corre
   * en su propio hilo y puede entregar un veredicto de un repo rápido mientras este método sigue
   * con los demás; entregar tarde dejaría ese veredicto llegando antes de que IMPLEMENTATION
   * conste en el ciclo de vida. No hace nada si la integración con GitHub está apagada: no se ha
   * hecho nada, así que no hay nada que entregar.
   *
   * @param correction qué hay que corregir, o null si es el primer arranque
   */
  public void openForIssue(final String issueKey, final String correction,
      final Consumer<PhaseOutcome> onOutcome) {
    if (!properties.isEnabled()) {
      return;
    }
    // Solo para decidir, en el catch, si la selección ya se cerró o si lo que falló fue la
    // implementación; no se devuelve a nadie.
    final List<PhaseOutcome> emitted = new ArrayList<>();
    try {
      JiraIssueDto issue = jiraClient.getIssue(issueKey);
      if (issue == null) {
        emit(emitted, selectionFailed("no se pudo leer la tarea de Jira",
            FailureReason.EXTERNAL_SERVICE_UNAVAILABLE, EvidenceSource.JIRA), onOutcome);
        return;
      }
      List<GithubIntegrationProperties.Repo> candidates = repoResolver.resolveCandidates(issue);
      if (candidates.isEmpty()) {
        log.info("Sin repos mapeados para {} (revisa maestro.github.projects)", issueKey);
        emit(emitted, selectionFailed(
            "sin repos mapeados para su sistema (revisa maestro.github.projects)",
            FailureReason.REPO_NOT_FOUND, EvidenceSource.SYSTEM), onOutcome);
        return;
      }
      final SelectorAgent.Selection selection = selectorAgent.select(issue, candidates);
      final List<String> repos = selection.repos();
      if (repos.isEmpty()) {
        log.info("Ningún repo seleccionado para {} entre los candidatos", issueKey);
        emit(emitted, selectionFailed("ningún repo seleccionado entre los candidatos", null,
            EvidenceSource.SYSTEM), onOutcome);
        return;
      }
      emit(emitted, PhaseOutcome.of(TaskPhase.REPO_SELECTION, Recommendation.PASS,
          Evidence.of(EvidenceKind.DECISION, "Repos elegidos (" + selection.method() + "): "
              + String.join(", ", repos), EvidenceSource.SYSTEM)), onOutcome);

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
        // Igual con el track: es del PEDIDO original, no cambia porque haya que corregir algo.
        // reroute() abre IMPLEMENTATION directamente para las correcciones, sin pasar por PLAN.
        emit(emitted, planOutcome(plannerAgent.classifyTrack(issueKey, summary, description)),
            onOutcome);
      }

      // En una corrección, al coder se le da la descripción original MÁS lo que hay que arreglar:
      // sin el original perdería el contexto de qué se pedía.
      final String brief = correction == null ? description
          : description + "\n\n## Corrección solicitada\n" + correction;

      final List<String> coded =
          openPullRequests(issueKey, summary, brief, repos, correction, emitted, onOutcome);

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
      final boolean implementationClosed = emitted.stream()
          .anyMatch(o -> o.phase() == TaskPhase.IMPLEMENTATION && o.isAggregate());
      if (emitted.isEmpty()) {
        emit(emitted, selectionFailed(reason, null, null), onOutcome);
      } else if (!implementationClosed) {
        emit(emitted, PhaseOutcome.of(TaskPhase.IMPLEMENTATION, Recommendation.FAIL,
            Evidence.of(EvidenceKind.REASON, reason)), onOutcome);
      }
    }
  }

  /** Añade el resultado al histórico local (solo para el propio método) y lo entrega YA. */
  private static void emit(final List<PhaseOutcome> track, final PhaseOutcome outcome,
      final Consumer<PhaseOutcome> onOutcome) {
    track.add(outcome);
    onOutcome.accept(outcome);
  }

  /**
   * Reintenta el coder sobre una PR que YA existe (rama {@code branch}, PR {@code prNumber}), en
   * vez de abrir una nueva: GitHub actualiza la PR sola en cuanto llega un commit a la rama.
   * Lo llama {@link es.colorbaby.microservices.dev.relay.control.correction.CorrectionService}
   * tras clasificar la causa de un fallo de verificación (CODE o PLAN). Best-effort.
   *
   * @param diagnosis por qué falló el intento anterior, para que el coder (y, si {@code replan},
   *                  el planner) lo tengan en cuenta
   * @param replan    si hay que replantear el enfoque antes de tocar código (causa PLAN) o basta
   *                  con que el coder corrija directamente (causa CODE)
   */
  public void retryOnSameBranch(final String issueKey, final String repo, final String branch,
      final int prNumber, final String diagnosis, final boolean replan,
      final Consumer<PhaseOutcome> onOutcome) {
    if (!properties.isEnabled()) {
      return;
    }
    try {
      final JiraIssueDto issue = jiraClient.getIssue(issueKey);
      final JiraIssueDtoFields fields = issue == null ? null : issue.getFields();
      final String summary =
          fields == null || fields.getSummary() == null ? issueKey : fields.getSummary();
      final String description = (fields == null
          ? "" : JiraTextExtractor.extractPlainText(fields.getDescription()))
          + "\n\n## El intento anterior no compiló\n" + diagnosis;

      final String plan = replan ? planWithAgent(issueKey, repo, branch, summary, description) : "";
      final AgentExecutionResult coderResult =
          codeWithAgent(issueKey, repo, branch, summary, description, plan);

      if (coderResult.status() == AgentStatus.NEEDS_INPUT) {
        jiraClient.addComment(issueKey, "❓ " + coderResult.summary()
            + "\n\nResponde en un comentario y sixai retoma desde donde se quedó.");
        onOutcome.accept(PhaseOutcome.forRepo(TaskPhase.IMPLEMENTATION, Recommendation.BLOCKED, repo,
            Evidence.of(EvidenceKind.REASON, coderResult.summary(), EvidenceSource.AGENT)));
        onOutcome.accept(PhaseOutcome.of(TaskPhase.IMPLEMENTATION, Recommendation.BLOCKED,
            Evidence.of(EvidenceKind.REASON, "esperando aclaración en " + repo, EvidenceSource.SYSTEM)));
        return;
      }

      final boolean coded = coderResult.status() == AgentStatus.COMPLETED
          && coderResult.summary() != null && !coderResult.summary().isBlank();
      final List<Evidence> evidence = new ArrayList<>();
      evidence.add(Evidence.of(EvidenceKind.PR, "#" + prNumber + " (" + branch + ", reintento)",
          EvidenceSource.GITHUB));
      if (coded) {
        taskRecorder.record(issueKey, TaskEventType.CODE_GENERATED, "sixai", repo);
        jiraClient.addComment(issueKey, "He corregido la PR de " + repo + " (#" + prNumber
            + "); vuelvo a comprobar que compila.");
        final String review =
            reviewWithAgent(issueKey, repo, branch, summary, description, coderResult, prNumber);
        if (review != null) {
          evidence.add(Evidence.of(EvidenceKind.REVIEW, review, EvidenceSource.AGENT));
        }
      } else {
        evidence.add(Evidence.of(EvidenceKind.REASON,
            "sin código del coder en el reintento: " + coderOutcome(coderResult),
            coderReasonCode(coderResult), EvidenceSource.AGENT));
      }
      final Recommendation verdict = coded ? Recommendation.PASS : Recommendation.FAIL;
      // Por repo primero, agregado después (cierra la nueva iteración) y la verificación al
      // final: el mismo orden que en el arranque normal.
      onOutcome.accept(new PhaseOutcome(TaskPhase.IMPLEMENTATION, verdict, repo, evidence,
          PhaseOutcome.SIXAI));
      onOutcome.accept(PhaseOutcome.of(TaskPhase.IMPLEMENTATION, verdict,
          Evidence.of(EvidenceKind.REASON, "reintento sobre la misma PR en " + repo,
              EvidenceSource.SYSTEM)));
      onOutcome.accept(verificationService.verify(issueKey, repo, branch, prNumber));
    } catch (RuntimeException e) {
      log.error("Error reintentando {} en {}: {}", issueKey, repo, e.getMessage());
      onOutcome.accept(PhaseOutcome.of(TaskPhase.IMPLEMENTATION, Recommendation.FAIL,
          Evidence.of(EvidenceKind.REASON, "error reintentando: " + e.getMessage(),
              EvidenceSource.SYSTEM)));
    }
  }

  /**
   * Retoma al coder tras la respuesta de una persona a una pregunta pendiente (NEEDS_INPUT).
   * Best-effort: si no había nada pendiente para esa tarea/repo, no hace nada.
   *
   * <p>Simplificación de esta primera versión (bucle de aclaraciones, fase 2 de la hoja de ruta):
   * el título y el cuerpo de la PR se reconstruyen con el resumen/descripción ACTUALES de Jira, no
   * con los que tenía la ejecución al pararse (si la pregunta vino de un ciclo de corrección, el
   * propio coder sí tuvo ese contexto — viaja en la ejecución guardada — pero el título de la PR
   * no llevará "(corrección)"); tampoco se vuelve a publicar el informe de entrega. Ninguna de las
   * dos cosas afecta al código que se commitea.
   */
  public void resumeAfterAnswer(final String issueKey, final String repo, final String answer,
      final Consumer<PhaseOutcome> onOutcome) {
    if (!properties.isEnabled()) {
      return;
    }
    try {
      final Optional<AgentExecutionResult> resumed =
          agentRuntime.resume(issueKey, repo, CoderAgent.ID, answer);
      if (resumed.isEmpty()) {
        log.warn("No hay nada pendiente de respuesta para {} en {}", issueKey, repo);
        return;
      }
      final AgentExecutionResult coderResult = resumed.get();
      if (coderResult.status() == AgentStatus.NEEDS_INPUT) {
        // Otra pregunta más: se vuelve a bloquear, igual que la primera vez.
        taskRecorder.record(issueKey, TaskEventType.AGENT_NEEDS_INPUT, "sixai",
            repo + ": " + coderResult.summary());
        jiraClient.addComment(issueKey, "❓ " + coderResult.summary()
            + "\n\nResponde en un comentario y sixai retoma desde donde se quedó.");
        onOutcome.accept(PhaseOutcome.forRepo(TaskPhase.IMPLEMENTATION, Recommendation.BLOCKED, repo,
            Evidence.of(EvidenceKind.REASON, coderResult.summary(), EvidenceSource.AGENT)));
        onOutcome.accept(PhaseOutcome.of(TaskPhase.IMPLEMENTATION, Recommendation.BLOCKED,
            Evidence.of(EvidenceKind.REASON, "esperando aclaración en " + repo, EvidenceSource.SYSTEM)));
        return;
      }
      taskRecorder.record(issueKey, TaskEventType.AGENT_RESUMED, "sixai", repo);

      final String branch = String.valueOf(coderResult.data().getOrDefault("branch", ""));
      if (branch.isBlank()) {
        log.error("El coder no dejó la rama al reanudar {} en {}", issueKey, repo);
        onOutcome.accept(PhaseOutcome.of(TaskPhase.IMPLEMENTATION, Recommendation.FAIL,
            Evidence.of(EvidenceKind.REASON, "el coder no dejó la rama al reanudar " + repo,
                FailureReason.AGENT_ERROR, EvidenceSource.AGENT)));
        return;
      }

      final JiraIssueDto issue = jiraClient.getIssue(issueKey);
      final JiraIssueDtoFields fields = issue == null ? null : issue.getFields();
      final String summary =
          fields == null || fields.getSummary() == null ? issueKey : fields.getSummary();
      final String description =
          fields == null ? "" : JiraTextExtractor.extractPlainText(fields.getDescription());
      final String base = properties.getBaseBranch();
      final boolean coded = coderResult.status() == AgentStatus.COMPLETED
          && coderResult.summary() != null && !coderResult.summary().isBlank();

      if (!coded) {
        githubClient.putFile(repo, branch, ".sixai/" + issueKey + ".md",
            placeholderFile(issueKey, summary, description), "chore(sixai): arranque de " + issueKey);
      }
      final GithubClient.PullRequest pr = githubClient.createPullRequest(
          repo, branch, base, "sixai · " + issueKey + " · " + summary,
          prBody(issueKey, summary, description), true);
      log.info("Draft-PR abierta en {} hacia {} tras retomar: {}", repo, base, pr.url());
      taskRecorder.record(issueKey, TaskEventType.PR_OPENED, "sixai",
          repo + " #" + pr.number() + " (" + branch + " → " + base + "): " + pr.url());
      jiraClient.addComment(issueKey, "Sigo con esto — abrí la PR de " + repo + ": " + pr.url());

      final List<Evidence> evidence = new ArrayList<>();
      evidence.add(new Evidence(EvidenceKind.PR,
          "#" + pr.number() + " (" + branch + " → " + base + ")", pr.url(), EvidenceSource.GITHUB));
      if (coded) {
        taskRecorder.record(issueKey, TaskEventType.CODE_GENERATED, "sixai", repo);
      } else {
        evidence.add(Evidence.of(EvidenceKind.REASON,
            "sin código del coder: " + coderOutcome(coderResult), coderReasonCode(coderResult),
            EvidenceSource.AGENT));
      }
      final Recommendation verdict = coded ? Recommendation.PASS : Recommendation.FAIL;
      // Por repo primero, agregado después (cierra lo que estaba BLOCKED) y la verificación al
      // final: el mismo orden que exige el ciclo de vida en el arranque normal.
      onOutcome.accept(new PhaseOutcome(TaskPhase.IMPLEMENTATION, verdict, repo, evidence,
          PhaseOutcome.SIXAI));
      onOutcome.accept(PhaseOutcome.of(TaskPhase.IMPLEMENTATION, verdict,
          Evidence.of(EvidenceKind.REASON, "retomado tras aclaración en " + repo,
              EvidenceSource.SYSTEM)));
      onOutcome.accept(verificationService.verify(issueKey, repo, branch, pr.number()));
    } catch (RuntimeException e) {
      log.error("Error reanudando {} en {}: {}", issueKey, repo, e.getMessage());
      onOutcome.accept(PhaseOutcome.of(TaskPhase.IMPLEMENTATION, Recommendation.FAIL,
          Evidence.of(EvidenceKind.REASON, "error reanudando: " + e.getMessage(),
              EvidenceSource.SYSTEM)));
    }
  }

  /**
   * Abre las PRs y devuelve las líneas de resumen de aquellas en las que el coder SÍ escribió
   * código: son las que dan contenido al informe (si no hay ninguna, no hay nada que informar).
   * Deja en {@code outcomes} un resultado de IMPLEMENTATION por repo y, al final, el agregado;
   * los resultados de VERIFICATION se acumulan aparte y se entregan DESPUÉS del agregado de
   * IMPLEMENTATION, o el ciclo de vida los vería fuera de orden (todavía en IMPLEMENTATION).
   */
  private List<String> openPullRequests(
      final String issueKey, final String summary, final String description,
      final List<String> repos, final String correction, final List<PhaseOutcome> outcomes,
      final Consumer<PhaseOutcome> onOutcome) {

    String title = (correction == null ? "sixai · " : "sixai (corrección) · ")
        + issueKey + " · " + summary;
    String body = prBody(issueKey, summary, description);
    String placeholder = placeholderFile(issueKey, summary, description);

    List<String> codedRepos = new ArrayList<>();
    List<String> links = new ArrayList<>();
    List<PhaseOutcome> verificationOutcomes = new ArrayList<>();
    List<String> blockedRepos = new ArrayList<>();
    String blockingQuestion = null;
    for (String repo : repos) {
      String branch = properties.getBranchPrefix() + issueKey + "-" + Instant.now().getEpochSecond();

      if (properties.isDryRun()) {
        log.info("[DRY-RUN] Abriría draft-PR en {} (rama {})", repo, branch);
        emit(outcomes, PhaseOutcome.forRepo(TaskPhase.IMPLEMENTATION, Recommendation.SKIPPED, repo,
            Evidence.of(EvidenceKind.REASON, "dry-run: no se abre PR", EvidenceSource.SYSTEM)),
            onOutcome);
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

        if (coderResult.status() == AgentStatus.NEEDS_INPUT) {
          // El coder necesita que alguien conteste antes de seguir: se para aquí, sin placeholder
          // ni PR. Toda la fase queda BLOCKED hasta la respuesta (ver resumeAfterAnswer): no tiene
          // sentido cerrar IMPLEMENTATION con otros repos mientras uno sigue con esto a medias.
          blockedRepos.add(repo);
          blockingQuestion = coderResult.summary();
          taskRecorder.record(issueKey, TaskEventType.AGENT_NEEDS_INPUT, "sixai",
              repo + ": " + blockingQuestion);
          emit(outcomes, PhaseOutcome.forRepo(TaskPhase.IMPLEMENTATION, Recommendation.BLOCKED, repo,
              Evidence.of(EvidenceKind.REASON, blockingQuestion, EvidenceSource.AGENT)), onOutcome);
          continue;
        }

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
            "#" + pr.number() + " (" + branch + " → " + base + ")", pr.url(),
            EvidenceSource.GITHUB));
        if (!plan.isBlank()) {
          evidence.add(Evidence.of(EvidenceKind.PLAN, plan, EvidenceSource.AGENT));
        }
        if (coded) {
          taskRecorder.record(issueKey, TaskEventType.CODE_GENERATED, "sixai", repo);
          codedRepos.add("- **" + repo + "** — PR [#" + pr.number() + "](" + pr.url() + "), rama `"
              + branch + "` → `" + base + "`");
          // El resumen del coder, tal cual, para poder comprobar después (Fase 6) si cubre los
          // criterios de aceptación de PLAN — sin esto, VERIFICATION no tendría con qué comparar.
          evidence.add(Evidence.of(EvidenceKind.REPORT, coderResult.summary(), EvidenceSource.AGENT));
          // El reviewer solo lee lo que el coder ya commiteó; su veredicto es una opinión más
          // para quien apruebe, nunca un bloqueo.
          final String review =
              reviewWithAgent(issueKey, repo, branch, summary, description, coderResult, pr.number());
          if (review != null) {
            evidence.add(Evidence.of(EvidenceKind.REVIEW, review, EvidenceSource.AGENT));
          }
        } else {
          evidence.add(Evidence.of(EvidenceKind.REASON,
              "sin código del coder: " + coderOutcome(coderResult),
              coderReasonCode(coderResult), EvidenceSource.AGENT));
        }
        emit(outcomes, new PhaseOutcome(TaskPhase.IMPLEMENTATION,
            coded ? Recommendation.PASS : Recommendation.FAIL, repo, evidence, PhaseOutcome.SIXAI),
            onOutcome);

        // Se manda compilar la rama YA, para que cuando alguien mire la PR sepa si se sostiene.
        // El veredicto tarda unos minutos y llega solo, a la tarea y al panel. Su resultado
        // (STARTED/SKIPPED/FAIL) se acumula aparte: no se entrega hasta cerrar IMPLEMENTATION.
        verificationOutcomes.add(verificationService.verify(issueKey, repo, branch, pr.number()));
      } catch (RuntimeException e) {
        log.error("No se pudo abrir la PR en {} para {}: {}", repo, issueKey, e.getMessage());
        emit(outcomes, PhaseOutcome.forRepo(TaskPhase.IMPLEMENTATION, Recommendation.FAIL, repo,
            Evidence.of(EvidenceKind.REASON, "no se pudo abrir la PR: " + e.getMessage(),
                FailureReason.EXTERNAL_SERVICE_UNAVAILABLE, EvidenceSource.GITHUB)),
            onOutcome);
      }
    }

    if (!blockedRepos.isEmpty()) {
      // Toda la fase se queda BLOCKED a la espera de la respuesta; no se cierra IMPLEMENTATION
      // (ni se comentan los links, ni se publica el informe) hasta que resumeAfterAnswer termine.
      jiraClient.addComment(issueKey, "❓ " + blockingQuestion
          + "\n\nResponde en un comentario y sixai retoma desde donde se quedó.");
      emit(outcomes, PhaseOutcome.of(TaskPhase.IMPLEMENTATION, Recommendation.BLOCKED,
          Evidence.of(EvidenceKind.REASON, "esperando aclaración en " + String.join(", ", blockedRepos),
              EvidenceSource.SYSTEM)), onOutcome);
      return codedRepos;
    }

    // El veredicto va ANTES del comentario: si Jira falla al comentar, las PRs y el código ya
    // existen, y la implementación no debe quedar como fallida por eso.
    final PhaseOutcome implementation =
        implementationVerdict(repos.size(), codedRepos.size(), properties.isDryRun());
    emit(outcomes, implementation, onOutcome);
    if (!links.isEmpty()) {
      jiraClient.addComment(issueKey,
          "sixai ha arrancado el trabajo abriendo estas PRs:\n" + String.join("\n", links));
    }
    if (verificationOutcomes.isEmpty() && implementation.recommendation() == Recommendation.SKIPPED) {
      // Nada que verificar (dry-run global: ningún repo llegó a abrir PR): sin este cierre,
      // VERIFICATION se quedaría IN_PROGRESS para siempre y la tarea nunca llegaría a APPROVAL.
      emit(outcomes, PhaseOutcome.of(TaskPhase.VERIFICATION, Recommendation.SKIPPED,
          Evidence.of(EvidenceKind.REASON, "sin PRs que verificar (dry-run)",
              EvidenceSource.SYSTEM)), onOutcome);
    } else {
      verificationOutcomes.forEach(outcome -> emit(outcomes, outcome, onOutcome));
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
            + (dryRun ? " (dry-run)" : ""), EvidenceSource.SYSTEM));
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

  /**
   * Causa reconocida de por qué el coder no dejó código, cuando {@link #coderOutcome} tiene una
   * que encaja: {@code DISABLED}/{@code DRY_RUN} son decisiones de configuración, no fallos, así
   * que no llevan código.
   */
  private static FailureReason coderReasonCode(final AgentExecutionResult result) {
    if (result.status() != AgentStatus.COMPLETED) {
      return FailureReason.AGENT_ERROR;
    }
    final Object outcome = result.data().get(CoderAgent.DATA_OUTCOME);
    if (!(outcome instanceof String name)) {
      return null;
    }
    return switch (name) {
      case "NO_CHANGES" -> FailureReason.NO_CHANGES;
      case "LLM_ERROR", "TRUNCATED", "GUARD_REJECTED", "COMMIT_FAILED" -> FailureReason.AGENT_ERROR;
      default -> null;
    };
  }

  private static PhaseOutcome selectionFailed(final String reason, final FailureReason reasonCode,
      final EvidenceSource source) {
    return PhaseOutcome.of(TaskPhase.REPO_SELECTION, Recommendation.FAIL,
        Evidence.of(EvidenceKind.REASON, reason, reasonCode, source));
  }

  /**
   * Cierra PLAN con el track y, si los hay, los criterios de aceptación — todavía no se exige que
   * VERIFICATION los cumpla (eso es la Fase 6 del roadmap grande, sin diseñar aún); por ahora esto
   * es visibilidad, no un gate.
   */
  private static PhaseOutcome planOutcome(final PlannerAgent.TrackClassification classification) {
    final List<Evidence> evidence = new ArrayList<>();
    evidence.add(Evidence.of(EvidenceKind.DECISION,
        "Track: " + classification.track()
            + (classification.rationale() == null || classification.rationale().isBlank()
                ? "" : " — " + classification.rationale()),
        EvidenceSource.AGENT));
    if (!classification.acceptanceCriteria().isEmpty()) {
      evidence.add(Evidence.of(EvidenceKind.PLAN,
          "Criterios de aceptación:\n- " + String.join("\n- ", classification.acceptanceCriteria()),
          EvidenceSource.AGENT));
    }
    return new PhaseOutcome(TaskPhase.PLAN, Recommendation.PASS, null, evidence, PhaseOutcome.SIXAI);
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

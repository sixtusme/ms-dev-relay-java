package es.colorbaby.microservices.dev.relay.approval;

import es.colorbaby.microservices.dev.relay.activity.TaskEventType;
import es.colorbaby.microservices.dev.relay.activity.TaskRecorder;
import es.colorbaby.microservices.dev.relay.config.ApprovalProperties;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentBatchRepository;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentCoordinator;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentPhase;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentService;
import es.colorbaby.microservices.dev.relay.deploy.DeploymentStatus;
import es.colorbaby.microservices.dev.relay.config.GithubIntegrationProperties;
import es.colorbaby.microservices.dev.relay.github.client.GithubClient;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFields;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraUserDto;
import es.colorbaby.microservices.dev.relay.pullrequest.RepoResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Aprobación de una issue desde el front {@code /sixai}: mergea a {@code develop} las PRs abiertas
 * de sixai de esa issue y arranca el despliegue a PRE de cada repo (build de develop → imagen en
 * Harbor → job de deploy). Cuando el {@link DeploymentCoordinator} ve que todos pasaron, reasigna
 * al informador y mueve la tarea a TEST.
 *
 * <p>No necesita base de datos: las PRs pendientes se reconstruyen leyendo GitHub (PRs abiertas cuya
 * rama empieza por {@code sixai/<ISSUE>-}). Todo depende de {@code maestro.approval.enabled}; con
 * {@code dry-run} loguea qué mergearía sin tocar nada.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalService {

  private final GithubClient githubClient;
  private final JiraClient jiraClient;
  private final RepoResolver repoResolver;
  private final DeploymentService deploymentService;
  private final DeploymentBatchRepository batches;
  private final TaskRecorder taskRecorder;
  private final GithubIntegrationProperties githubProperties;
  private final ApprovalProperties properties;

  /**
   * Aprobaciones que se están atendiendo ahora mismo. Cierra la ventana del doble clic: dos
   * peticiones a la vez listarían las mismas PRs abiertas antes de que ninguna hubiera mergeado, y
   * las dos arrancarían su lote.
   */
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

  /**
   * Aprueba una issue: mergea sus PRs a develop y arranca el despliegue a PRE. Best-effort.
   *
   * @param issueKey   tarea a aprobar
   * @param approvedBy quién lo aprueba (cabecera {@code X-Username} que pone el gateway)
   */
  public void approve(final String issueKey, final String approvedBy) {
    if (!properties.isEnabled()) {
      return;
    }
    if (!inFlight.add(issueKey)) {
      log.info("Ya se está atendiendo una aprobación de {}; se ignora la repetida", issueKey);
      return;
    }
    try {
      doApprove(issueKey, approvedBy);
    } catch (RuntimeException e) {
      log.error("Error aprobando {}: {}", issueKey, e.getMessage());
    } finally {
      inFlight.remove(issueKey);
    }
  }

  private void doApprove(final String issueKey, final String approvedBy) {
    // El candado de arriba solo dura lo que dura esta llamada, y un despliegue dura ~30 minutos.
    // Sin esta segunda comprobación, volver a pulsar "Aprobar" un rato después lanzaría un segundo
    // build del mismo repo sobre el anterior.
    if (!batches.findByIssueKeyAndStatus(issueKey, DeploymentStatus.RUNNING).isEmpty()) {
      log.info("{} ya tiene un despliegue en curso; no se aprueba otra vez", issueKey);
      return;
    }
    final JiraIssueDto issue = jiraClient.getIssue(issueKey);
    if (issue == null) {
      return;
    }
    final Map<String, Integer> prByRepo = findOpenSixaiPullRequests(issueKey, issue);
    if (prByRepo.isEmpty()) {
      log.info("Sin PRs de sixai abiertas para {}", issueKey);
      return;
    }
    if (properties.isDryRun()) {
      log.info("[DRY-RUN] Mergearía a develop para {}: {}", issueKey, prByRepo);
      return;
    }
    taskRecorder.record(issueKey, TaskEventType.APPROVED, actor(approvedBy),
        "aprobado el merge de " + prByRepo.size() + " PR(s) a "
            + githubProperties.getBaseBranch());
    mergeAll(issueKey, issue, prByRepo);
  }

  /**
   * Quién aprobó. Es el dato más importante de toda la línea de tiempo —es la compuerta que autoriza
   * meter código en develop y desplegarlo—, así que cuando no llega la identidad se dice, en vez de
   * apuntar un genérico que parezca una persona.
   */
  private String actor(final String approvedBy) {
    return approvedBy == null || approvedBy.isBlank()
        ? "front /sixai (sin identificar)" : approvedBy;
  }

  private void mergeAll(final String issueKey, final JiraIssueDto issue,
      final Map<String, Integer> prByRepo) {
    final String base = githubProperties.getBaseBranch();
    final Map<String, String> branchByRepo = new LinkedHashMap<>();
    for (final Map.Entry<String, Integer> entry : prByRepo.entrySet()) {
      try {
        githubClient.mergePullRequest(entry.getKey(), entry.getValue(), properties.getMergeMethod());
        log.info("PR #{} de {} mergeada a {}", entry.getValue(), entry.getKey(), base);
        taskRecorder.record(issueKey, TaskEventType.MERGED, "sixai",
            entry.getKey() + " #" + entry.getValue() + " → " + base);
        branchByRepo.put(entry.getKey(), base);
      } catch (RuntimeException e) {
        log.error("No se pudo mergear la PR de {} para {}: {}",
            entry.getKey(), issueKey, e.getMessage());
        jiraClient.addComment(issueKey, "⚠️ No se pudo mergear la PR de " + entry.getKey()
            + " a " + base + ": " + e.getMessage());
      }
    }
    if (branchByRepo.isEmpty()) {
      return;
    }
    // El lote se crea con lo que realmente arranca; lo que no, se dice en la tarea.
    final DeploymentService.StartResult result = deploymentService.startBatch(
        issueKey, DeploymentPhase.PRE, reporterAccountId(issue), branchByRepo);
    reportSkipped(issueKey, result);
  }

  // PRs abiertas de sixai (rama sixai/<ISSUE>-…) por repo candidato de la issue.
  private Map<String, Integer> findOpenSixaiPullRequests(final String issueKey,
      final JiraIssueDto issue) {
    final String base = githubProperties.getBaseBranch();
    final String headPrefix = githubProperties.getBranchPrefix() + issueKey + "-";
    final Map<String, Integer> prByRepo = new LinkedHashMap<>();
    for (final GithubIntegrationProperties.Repo repo : repoResolver.resolveCandidates(issue)) {
      final var prs = githubClient.listOpenPullRequests(repo.getName(), base);
      for (final GithubClient.PullRequest pr : prs) {
        if (pr.head() != null && pr.head().startsWith(headPrefix)) {
          prByRepo.put(repo.getName(), pr.number());
          break;
        }
      }
    }
    return prByRepo;
  }

  /** Si algún repo se quedó fuera del despliegue, se dice en la tarea en vez de callarlo. */
  private void reportSkipped(final String issueKey, final DeploymentService.StartResult result) {
    if (result.skipped().isEmpty()) {
      return;
    }
    jiraClient.addComment(issueKey, "ℹ️ Estos repos no se despliegan por el pipeline y quedan "
        + "fuera: " + String.join(", ", result.skipped()) + ".");
  }

  private String reporterAccountId(final JiraIssueDto issue) {
    final JiraIssueDtoFields fields = issue.getFields();
    final JiraUserDto reporter = fields == null ? null : fields.getReporter();
    return reporter == null ? null : reporter.getAccountId();
  }
}

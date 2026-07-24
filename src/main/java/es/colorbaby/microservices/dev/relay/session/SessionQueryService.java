package es.colorbaby.microservices.dev.relay.session;

import es.colorbaby.microservices.dev.relay.config.GithubIntegrationProperties;
import es.colorbaby.microservices.dev.relay.github.client.GithubClient;
import es.colorbaby.microservices.dev.relay.verification.VerificationRun;
import es.colorbaby.microservices.dev.relay.verification.VerificationRunRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reconstruye las sesiones de sixai leyendo GitHub: recorre las PRs abiertas de todos los repos
 * configurados, se queda con las de sixai (rama {@code sixai/<ISSUE>-…} hacia develop) y las agrupa
 * por issue. Sin base de datos: es la foto de lo que hay abierto ahora. Alimenta la vista /sixai.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionQueryService {

  private final GithubClient githubClient;
  private final VerificationRunRepository verifications;
  private final GithubIntegrationProperties properties;

  /** Sesiones abiertas ahora mismo (una por issue con PRs de sixai). Vacío si GitHub está apagado. */
  public List<SixaiSessionDto> listSessions() {
    if (!properties.isEnabled()) {
      return List.of();
    }
    final String base = properties.getBaseBranch();
    final String prefix = properties.getBranchPrefix();
    final Map<String, List<SixaiPrDto>> byIssue = new LinkedHashMap<>();

    for (final String repo : allRepos()) {
      final List<GithubClient.PullRequest> prs;
      try {
        prs = githubClient.listOpenPullRequests(repo, base);
      } catch (RuntimeException e) {
        log.warn("No se pudieron listar PRs de {}: {}", repo, e.getMessage());
        continue;
      }
      for (final GithubClient.PullRequest pr : prs) {
        final String head = pr.head();
        if (head == null || !head.startsWith(prefix)) {
          continue;
        }
        final String issueKey = issueKeyFromHead(head, prefix);
        // El veredicto de "¿esto compila?" viaja con la PR: es lo que hay que saber ANTES de
        // aprobar, y el botón de aprobar está justo al lado.
        final Optional<VerificationRun> check = verificationOf(issueKey, repo);
        byIssue.computeIfAbsent(issueKey, k -> new ArrayList<>())
            .add(new SixaiPrDto(repo, pr.number(), pr.url(), head, base, verdict(check),
                check.map(VerificationRun::getFailureReason).orElse(null)));
      }
    }

    final List<SixaiSessionDto> sessions = new ArrayList<>();
    byIssue.forEach((issueKey, prs) -> sessions.add(new SixaiSessionDto(issueKey, issueKey, prs)));
    return sessions;
  }

  /** La verificación más reciente de esa PR (la última del repo dentro de la tarea). */
  private Optional<VerificationRun> verificationOf(final String issueKey, final String repo) {
    return verifications.findByIssueKeyOrderByIdDesc(issueKey).stream()
        .filter(run -> repo.equals(run.getRepo()))
        .findFirst();
  }

  private static String verdict(final Optional<VerificationRun> check) {
    if (check.isEmpty()) {
      return SixaiPrDto.NONE;
    }
    return switch (check.get().getStatus()) {
      case RUNNING -> SixaiPrDto.PENDING;
      case SUCCEEDED -> SixaiPrDto.OK;
      case FAILED -> SixaiPrDto.FAILED;
    };
  }

  private Set<String> allRepos() {
    final Set<String> repos = new LinkedHashSet<>();
    for (final GithubIntegrationProperties.Project project : properties.getProjects()) {
      for (final GithubIntegrationProperties.Repo repo : project.getRepos()) {
        if (repo.getName() != null) {
          repos.add(repo.getName());
        }
      }
    }
    return repos;
  }

  // rama sixai/<ISSUE>-<epoch> → issueKey. Quita el prefijo y el sufijo "-<dígitos>" final.
  private static String issueKeyFromHead(final String head, final String prefix) {
    final String rest = head.substring(prefix.length());
    final int i = rest.lastIndexOf('-');
    if (i > 0 && !rest.substring(i + 1).isEmpty()
        && rest.substring(i + 1).chars().allMatch(Character::isDigit)) {
      return rest.substring(0, i);
    }
    return rest;
  }
}

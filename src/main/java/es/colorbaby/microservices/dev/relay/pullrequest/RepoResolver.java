package es.colorbaby.microservices.dev.relay.pullrequest;

import es.colorbaby.microservices.dev.relay.config.GithubIntegrationProperties;
import es.colorbaby.microservices.dev.relay.config.GithubIntegrationProperties.Project;
import es.colorbaby.microservices.dev.relay.config.GithubIntegrationProperties.Repo;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFields;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFieldsParent;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Decide a qué SISTEMA pertenece una tarea, y con ello sus repos candidatos. Cruza la clave de la
 * issue, el título, el nombre de la épica y las labels contra las palabras clave de
 * {@code maestro.github.projects}: el primer sistema que casa aporta sus repos candidatos. Acotar
 * esos candidatos al subconjunto que realmente hay que tocar es cosa de {@link RepoSelector}.
 */
@Component
@RequiredArgsConstructor
public class RepoResolver {

  private final GithubIntegrationProperties properties;

  /** Repos candidatos del sistema al que pertenece la issue (vacío si ninguno casa). */
  public List<Repo> resolveCandidates(final JiraIssueDto issue) {
    String haystack = systemHaystack(issue);
    for (Project project : properties.getProjects()) {
      boolean match = project.getKeywords().stream()
          .anyMatch(k -> k != null && !k.isBlank()
              && haystack.contains(k.toLowerCase(Locale.ROOT)));
      if (match) {
        return project.getRepos();
      }
    }
    return List.of();
  }

  // Texto que identifica el SISTEMA: clave + título + nombre de la épica + labels (en minúsculas).
  private String systemHaystack(final JiraIssueDto issue) {
    StringBuilder sb = new StringBuilder();
    if (issue.getKey() != null) {
      sb.append(issue.getKey()).append(' ');
    }
    JiraIssueDtoFields fields = issue.getFields();
    if (fields != null) {
      if (fields.getSummary() != null) {
        sb.append(fields.getSummary()).append(' ');
      }
      JiraIssueDtoFieldsParent parent = fields.getParent();
      if (parent != null && parent.getFields() != null
          && parent.getFields().getSummary() != null) {
        sb.append(parent.getFields().getSummary()).append(' ');
      }
      if (fields.getLabels() != null) {
        fields.getLabels().forEach(label -> sb.append(label).append(' '));
      }
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }
}

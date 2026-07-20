package es.colorbaby.microservices.dev.relay.pullrequest;

import es.colorbaby.microservices.dev.relay.config.GithubIntegrationProperties.Repo;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.jira.util.JiraTextExtractor;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDto;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFields;
import es.colorbaby.microservices.dev.relay.openapi.model.JiraIssueDtoFieldsParent;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * De los repos candidatos de un sistema, elige aquellos donde REALMENTE hay que implementar la
 * feature o corregir el bug, para no abrir PRs de más. Con la IA encendida
 * ({@code maestro.llm.enabled=true}) lo decide el modelo a partir del título/descripción/épica y el
 * rol de cada repo; con la IA apagada (o si el modelo falla), cae a un filtro determinista por las
 * palabras clave de cada repo.
 *
 * <p>Nunca devuelve vacío si hay candidatos: si no hay ninguna señal para acotar, abre en todos
 * (mejor una draft-PR de más, que el dev cierra, que quedarse corto y olvidar una capa).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepoSelector {

  private static final String SELECTION_SYSTEM_PROMPT =
      "Eres Sixai. Te doy una tarea de Jira (título, descripción y épica) y una lista de "
      + "repositorios candidatos, cada uno con su rol. Responde ÚNICAMENTE con los nombres exactos "
      + "de los repositorios donde hay que implementar la feature o corregir el bug, uno por línea, "
      + "sin explicaciones ni texto adicional. No incluyas repos que no haya que tocar. Si una "
      + "feature de backend necesita cambiar el contrato/spec además del servicio, incluye ambos. "
      + "Si dudas razonablemente de si un repo requiere cambios, inclúyelo.";

  private final LlmProperties llmProperties;
  private final LlmClient llmClient;

  /** Nombres de los repos donde abrir PR. Vacío solo si no había candidatos. */
  public List<String> select(final JiraIssueDto issue, final List<Repo> candidates) {
    if (candidates.isEmpty()) {
      return List.of();
    }
    if (candidates.size() == 1) {
      return List.of(candidates.get(0).getName());
    }

    if (llmProperties.isEnabled()) {
      List<String> byLlm = selectWithLlm(issue, candidates);
      if (!byLlm.isEmpty()) {
        return byLlm;
      }
      log.warn("El LLM no acotó repos para {}; uso el fallback por keywords", issue.getKey());
    }

    List<String> byKeywords = selectWithKeywords(issue, candidates);
    if (!byKeywords.isEmpty()) {
      return byKeywords;
    }

    log.warn("Sin señales para acotar repos de {}; abro PR en todos los candidatos", issue.getKey());
    return candidates.stream().map(Repo::getName).toList();
  }

  private List<String> selectWithLlm(final JiraIssueDto issue, final List<Repo> candidates) {
    String output;
    try {
      output = llmClient.complete(LlmRequest.of(SELECTION_SYSTEM_PROMPT,
          selectionPrompt(issue, candidates), LlmRoles.SELECTOR, issue.getKey()));
    } catch (RuntimeException e) {
      log.warn("Fallo del LLM acotando repos para {}: {}", issue.getKey(), e.getMessage());
      return List.of();
    }
    if (output == null || output.isBlank()) {
      return List.of();
    }
    String lower = output.toLowerCase(Locale.ROOT);
    // Robusto al formato: nos quedamos con los candidatos cuyo nombre aparece en la respuesta.
    return candidates.stream()
        .filter(repo -> lower.contains(repo.getName().toLowerCase(Locale.ROOT)))
        .map(Repo::getName)
        .toList();
  }

  private List<String> selectWithKeywords(final JiraIssueDto issue, final List<Repo> candidates) {
    String haystack = taskHaystack(issue);
    return candidates.stream()
        .filter(repo -> repo.getKeywords().stream()
            .anyMatch(k -> k != null && !k.isBlank()
                && haystack.contains(k.toLowerCase(Locale.ROOT))))
        .map(Repo::getName)
        .toList();
  }

  private String selectionPrompt(final JiraIssueDto issue, final List<Repo> candidates) {
    JiraIssueDtoFields fields = issue.getFields();
    String summary = fields == null || fields.getSummary() == null ? "" : fields.getSummary();
    String description = fields == null
        ? "" : JiraTextExtractor.extractPlainText(fields.getDescription());

    StringBuilder sb = new StringBuilder();
    sb.append("Título: ").append(summary).append('\n');
    sb.append("Épica: ").append(orElse(epicName(fields), "(sin épica)")).append('\n');
    sb.append("Descripción:\n").append(orElse(description, "(sin descripción)"));
    sb.append("\n\nRepositorios candidatos:\n");
    for (Repo repo : candidates) {
      sb.append("- ").append(repo.getName()).append(" — ")
          .append(orElse(repo.getRole(), "(sin rol)")).append('\n');
    }
    return sb.toString();
  }

  // Texto para el fallback: clave + título + descripción + épica + labels (en minúsculas).
  private String taskHaystack(final JiraIssueDto issue) {
    StringBuilder sb = new StringBuilder();
    if (issue.getKey() != null) {
      sb.append(issue.getKey()).append(' ');
    }
    JiraIssueDtoFields fields = issue.getFields();
    if (fields != null) {
      if (fields.getSummary() != null) {
        sb.append(fields.getSummary()).append(' ');
      }
      String description = JiraTextExtractor.extractPlainText(fields.getDescription());
      if (description != null) {
        sb.append(description).append(' ');
      }
      String epic = epicName(fields);
      if (epic != null) {
        sb.append(epic).append(' ');
      }
      if (fields.getLabels() != null) {
        fields.getLabels().forEach(label -> sb.append(label).append(' '));
      }
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }

  private static String epicName(final JiraIssueDtoFields fields) {
    if (fields == null) {
      return null;
    }
    JiraIssueDtoFieldsParent parent = fields.getParent();
    if (parent == null || parent.getFields() == null) {
      return null;
    }
    return parent.getFields().getSummary();
  }

  private static String orElse(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}

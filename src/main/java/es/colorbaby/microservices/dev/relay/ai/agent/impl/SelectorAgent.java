package es.colorbaby.microservices.dev.relay.ai.agent.impl;

import es.colorbaby.microservices.dev.relay.ai.agent.Agent;
import es.colorbaby.microservices.dev.relay.ai.agent.AgentContext;
import es.colorbaby.microservices.dev.relay.ai.agent.record.AgentResult;
import es.colorbaby.microservices.dev.relay.ai.agent.state.AgentStatus;
import es.colorbaby.microservices.dev.relay.ai.skill.Skill;
import es.colorbaby.microservices.dev.relay.ai.skill.SkillRegistry;
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
 * feature o corregir el bug, para no abrir PRs de más. Reemplaza al antiguo {@code RepoSelector}:
 * misma lógica exacta (LLM con fallback determinista por palabras clave de cada repo), ahora como
 * {@link Agent} de la arquitectura Maestro.
 *
 * <p>Nunca devuelve vacío si hay candidatos: si no hay ninguna señal para acotar, abre en todos
 * (mejor una draft-PR de más, que el dev cierra, que quedarse corto y olvidar una capa).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelectorAgent implements Agent {

  private static final String SKILL_ID = "repo-selection";

  // Fallback si, por lo que sea, la skill no está en el classpath.
  private static final String FALLBACK_SYSTEM_PROMPT =
      "Eres Sixai. Responde ÚNICAMENTE con los nombres exactos de los repositorios donde hay que "
      + "implementar la feature o corregir el bug, uno por línea, sin explicaciones.";

  private final LlmProperties llmProperties;
  private final LlmClient llmClient;
  private final SkillRegistry skillRegistry;

  @Override
  public String id() {
    return "selector";
  }

  @Override
  public String description() {
    return "Acota los repos candidatos de una tarea a aquellos donde realmente hay que tocar código.";
  }

  /** Nombres de los repos donde abrir PR. Vacío solo si no había candidatos. */
  public List<String> select(final JiraIssueDto issue, final List<Repo> candidates) {
    if (candidates.isEmpty()) {
      return List.of();
    }
    if (candidates.size() == 1) {
      return List.of(candidates.get(0).getName());
    }

    if (llmProperties.isEnabled()) {
      final List<String> byLlm = selectWithLlm(issue, candidates);
      if (!byLlm.isEmpty()) {
        return byLlm;
      }
      log.warn("El LLM no acotó repos para {}; uso el fallback por keywords", issue.getKey());
    }

    final List<String> byKeywords = selectWithKeywords(issue, candidates);
    if (!byKeywords.isEmpty()) {
      return byKeywords;
    }

    log.warn("Sin señales para acotar repos de {}; abro PR en todos los candidatos", issue.getKey());
    return candidates.stream().map(Repo::getName).toList();
  }

  /**
   * Camino genérico del {@link Agent}: pensado para cuando otro agente delegue aquí solo con
   * texto libre (sin la lista tipada de candidatos). El uso normal es {@link #select}.
   */
  @Override
  public AgentResult execute(final AgentContext context) {
    final String systemPrompt = skillPrompt();
    final String output;
    try {
      output = llmClient.complete(LlmRequest.of(
          systemPrompt, context.taskDescription(), LlmRoles.SELECTOR, context.issueKey()));
    } catch (RuntimeException e) {
      log.warn("Fallo del LLM en el selector para {}: {}", context.issueKey(), e.getMessage());
      return new AgentResult(AgentStatus.FAILED, e.getMessage(), List.of());
    }
    return new AgentResult(AgentStatus.COMPLETED, output == null ? "" : output, List.of());
  }

  private List<String> selectWithLlm(final JiraIssueDto issue, final List<Repo> candidates) {
    final String output;
    try {
      output = llmClient.complete(LlmRequest.of(skillPrompt(),
          selectionPrompt(issue, candidates), LlmRoles.SELECTOR, issue.getKey()));
    } catch (RuntimeException e) {
      log.warn("Fallo del LLM acotando repos para {}: {}", issue.getKey(), e.getMessage());
      return List.of();
    }
    if (output == null || output.isBlank()) {
      return List.of();
    }
    final String lower = output.toLowerCase(Locale.ROOT);
    // Robusto al formato: nos quedamos con los candidatos cuyo nombre aparece en la respuesta.
    return candidates.stream()
        .filter(repo -> lower.contains(repo.getName().toLowerCase(Locale.ROOT)))
        .map(Repo::getName)
        .toList();
  }

  private List<String> selectWithKeywords(final JiraIssueDto issue, final List<Repo> candidates) {
    final String haystack = taskHaystack(issue);
    return candidates.stream()
        .filter(repo -> repo.getKeywords().stream()
            .anyMatch(k -> k != null && !k.isBlank()
                && haystack.contains(k.toLowerCase(Locale.ROOT))))
        .map(Repo::getName)
        .toList();
  }

  private String selectionPrompt(final JiraIssueDto issue, final List<Repo> candidates) {
    final JiraIssueDtoFields fields = issue.getFields();
    final String summary = fields == null || fields.getSummary() == null ? "" : fields.getSummary();
    final String description = fields == null
        ? "" : JiraTextExtractor.extractPlainText(fields.getDescription());

    final StringBuilder sb = new StringBuilder();
    sb.append("Título: ").append(summary).append('\n');
    sb.append("Épica: ").append(orElse(epicName(fields), "(sin épica)")).append('\n');
    sb.append("Descripción:\n").append(orElse(description, "(sin descripción)"));
    sb.append("\n\nRepositorios candidatos:\n");
    for (final Repo repo : candidates) {
      sb.append("- ").append(repo.getName()).append(" — ")
          .append(orElse(repo.getRole(), "(sin rol)")).append('\n');
    }
    return sb.toString();
  }

  // Texto para el fallback: clave + título + descripción + épica + labels (en minúsculas).
  private String taskHaystack(final JiraIssueDto issue) {
    final StringBuilder sb = new StringBuilder();
    if (issue.getKey() != null) {
      sb.append(issue.getKey()).append(' ');
    }
    final JiraIssueDtoFields fields = issue.getFields();
    if (fields != null) {
      if (fields.getSummary() != null) {
        sb.append(fields.getSummary()).append(' ');
      }
      final String description = JiraTextExtractor.extractPlainText(fields.getDescription());
      if (description != null) {
        sb.append(description).append(' ');
      }
      final String epic = epicName(fields);
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
    final JiraIssueDtoFieldsParent parent = fields.getParent();
    if (parent == null || parent.getFields() == null) {
      return null;
    }
    return parent.getFields().getSummary();
  }

  private static String orElse(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private String skillPrompt() {
    return skillRegistry.find(SKILL_ID).map(Skill::instructions).orElse(FALLBACK_SYSTEM_PROMPT);
  }
}

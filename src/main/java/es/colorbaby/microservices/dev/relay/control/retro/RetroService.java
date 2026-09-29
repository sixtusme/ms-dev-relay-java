package es.colorbaby.microservices.dev.relay.control.retro;

import es.colorbaby.microservices.dev.relay.ai.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.ai.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.ai.llm.LlmRoles;
import es.colorbaby.microservices.dev.relay.config.GithubIntegrationProperties;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.config.RetroProperties;
import es.colorbaby.microservices.dev.relay.control.lifecycle.EvidenceEntry;
import es.colorbaby.microservices.dev.relay.control.lifecycle.LifecycleSnapshot;
import es.colorbaby.microservices.dev.relay.control.lifecycle.PhaseHistoryEntry;
import es.colorbaby.microservices.dev.relay.control.lifecycle.Recommendation;
import es.colorbaby.microservices.dev.relay.control.lifecycle.TaskLifecycle;
import es.colorbaby.microservices.dev.relay.github.client.GithubClient;
import java.util.Collection;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Retro de una tarea completada (mejoras-senior Fase 6, segunda mitad): si tuvo que corregirse por
 * el camino, escribe una lección corta en el {@code AGENTS.md} de cada repo que tocó, para que la
 * próxima vez (un agente o una persona) no repita el mismo tropiezo.
 *
 * <p>Deliberadamente lazy: una tarea que no necesitó ninguna corrección no genera retro — no hay
 * lección que escribir, y llenar el fichero de notas de tareas que salieron bien a la primera solo
 * sería ruido. La señal es simple: {@code remediation_iterations > 0} (al menos un ciclo de
 * corrección, manual o automático).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetroService {

  private static final String SYSTEM_PROMPT =
      "Eres Sixai. Te doy lo que falló durante una tarea ya completada (evidencia de fases "
      + "fallidas: qué se rompió y por qué). Escribe UNA lección corta y concreta (2-4 frases) "
      + "para que la próxima vez que alguien (persona o agente) toque este repo lo tenga en "
      + "cuenta. No repitas los hechos tal cual: la lección práctica, no el incidente. Si de "
      + "verdad no hay nada generalizable que aprender, responde una cadena vacía.";

  private static final String LESSONS_HEADER = "## Lecciones de sixai";

  private final RetroProperties properties;
  private final LlmClient llmClient;
  private final LlmProperties llmProperties;
  private final GithubClient githubClient;
  private final GithubIntegrationProperties githubProperties;
  private final TaskLifecycle taskLifecycle;

  /**
   * Al llegar {@code issueKey} a {@code DONE}. Best-effort: nunca lanza, y sin corrección de por
   * medio no hace nada.
   */
  public void afterTaskCompleted(final String issueKey, final Collection<String> repos) {
    if (!properties.isEnabled() || !llmProperties.isEnabled() || repos.isEmpty()) {
      return;
    }
    try {
      final Optional<LifecycleSnapshot> snapshot = taskLifecycle.snapshot(issueKey);
      if (snapshot.isEmpty() || snapshot.get().iteration() <= 0) {
        return;
      }
      final String signal = failureSignal(snapshot.get());
      if (signal.isBlank()) {
        return;
      }
      final String lesson = writeLesson(issueKey, signal);
      if (lesson == null || lesson.isBlank()) {
        return;
      }
      for (final String repo : repos) {
        appendLesson(repo, issueKey, lesson);
      }
    } catch (RuntimeException e) {
      log.warn("No se pudo hacer la retro de {}: {}", issueKey, e.getMessage());
    }
  }

  /** Todo lo que salió FAIL durante el recorrido, tal cual quedó explicado en su evidencia. */
  private static String failureSignal(final LifecycleSnapshot snapshot) {
    final StringBuilder text = new StringBuilder();
    for (final PhaseHistoryEntry entry : snapshot.history()) {
      for (final EvidenceEntry evidence : entry.evidence()) {
        if (evidence.recommendation() == Recommendation.FAIL && evidence.detail() != null
            && !evidence.detail().isBlank()) {
          text.append("- [").append(entry.phase()).append("] ").append(evidence.detail())
              .append('\n');
        }
      }
    }
    return text.toString();
  }

  private String writeLesson(final String issueKey, final String signal) {
    try {
      return llmClient.complete(
          LlmRequest.of(SYSTEM_PROMPT, signal, LlmRoles.RETRO, issueKey));
    } catch (RuntimeException e) {
      log.warn("El retro no pudo escribir la lección de {}: {}", issueKey, e.getMessage());
      return null;
    }
  }

  private void appendLesson(final String repo, final String issueKey, final String lesson) {
    if (properties.isDryRun()) {
      log.info("[DRY-RUN] Anotaría en {}/{} la lección de {}: {}", repo,
          properties.getTargetFile(), issueKey, lesson);
      return;
    }
    try {
      final String branch = githubProperties.getBaseBranch();
      final String path = properties.getTargetFile();
      final Optional<GithubClient.FileContent> existing =
          githubClient.getFileContent(repo, branch, path);
      final String current = existing.map(GithubClient.FileContent::content).orElse("");
      final String updated = appendToLessons(current, issueKey, lesson);
      githubClient.putFile(repo, branch, path, updated,
          "docs(sixai): lección de " + issueKey, existing.map(GithubClient.FileContent::sha).orElse(null));
      log.info("Lección de {} anotada en {}/{}", issueKey, repo, path);
    } catch (RuntimeException e) {
      log.warn("No se pudo anotar la lección de {} en {}: {}", issueKey, repo, e.getMessage());
    }
  }

  /**
   * Añade la lección bajo la cabecera de lecciones, al final del fichero. Simplificación
   * deliberada: si la cabecera existiera en mitad de un fichero con más secciones detrás, la nueva
   * lección se cuela igualmente al final del fichero entero, no justo debajo de la cabecera — para
   * un AGENTS.md dedicado (el caso normal) no importa; si hiciera falta insertar justo bajo la
   * cabecera habría que parsear secciones en vez de solo comprobar si el texto ya la contiene.
   */
  private static String appendToLessons(final String current, final String issueKey,
      final String lesson) {
    final String bullet = "- **" + issueKey + "**: " + lesson.strip();
    if (current == null || current.isBlank()) {
      return LESSONS_HEADER + "\n\n" + bullet + "\n";
    }
    if (current.contains(LESSONS_HEADER)) {
      return current.stripTrailing() + "\n" + bullet + "\n";
    }
    return current.stripTrailing() + "\n\n" + LESSONS_HEADER + "\n\n" + bullet + "\n";
  }
}

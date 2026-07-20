package es.colorbaby.microservices.dev.relay.build;

import es.colorbaby.microservices.dev.relay.config.JenkinsIntegrationProperties;
import es.colorbaby.microservices.dev.relay.config.LlmProperties;
import es.colorbaby.microservices.dev.relay.jenkins.client.JenkinsClient;
import es.colorbaby.microservices.dev.relay.jira.client.JiraClient;
import es.colorbaby.microservices.dev.relay.llm.LlmClient;
import es.colorbaby.microservices.dev.relay.llm.LlmRequest;
import es.colorbaby.microservices.dev.relay.llm.LlmRoles;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sondea en background los builds que sixai está vigilando (Fase 1: solo observa, no arregla nada).
 * Cuando un build termina, comenta el resultado en la issue de Jira; si falló y el LLM está activo,
 * añade un diagnóstico de la consola. Best-effort: nunca relanza y descarta el watch al terminar o
 * al agotar los intentos.
 *
 * <p>La auto-reparación (proponer un fix y reintentar) es la Fase 2; aquí no se toca ningún repo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JenkinsBuildObserver {

  private static final String DIAGNOSE_SYSTEM_PROMPT =
      "Eres Sixai. Te doy la consola (la cola) de un build de Jenkins que ha fallado. Resume en "
      + "pocas frases, en español, la causa más probable del fallo y qué habría que revisar. Sé "
      + "concreto y no inventes: si la consola no basta para saberlo, dilo.";

  private final JenkinsIntegrationProperties properties;
  private final BuildWatchRegistry registry;
  private final JenkinsClient jenkinsClient;
  private final JiraClient jiraClient;
  private final LlmClient llmClient;
  private final LlmProperties llmProperties;

  /** Barrido periódico. El intervalo es {@code maestro.jenkins.poll-interval-ms}. */
  @Scheduled(fixedDelayString = "${maestro.jenkins.poll-interval-ms:30000}")
  public void sweep() {
    if (!properties.isEnabled() || properties.isDryRun() || registry.isEmpty()) {
      return;
    }
    for (final BuildWatch watch : registry.snapshot()) {
      try {
        poll(watch);
      } catch (RuntimeException e) {
        log.warn("Fallo sondeando el build de {} ({}): {}",
            watch.getRepo(), watch.getIssueKey(), e.getMessage());
        if (watch.getAttempts() > properties.getMaxAttempts()) {
          registry.remove(watch);
        }
      }
    }
  }

  private void poll(final BuildWatch watch) {
    if (watch.incrementAttempts() > properties.getMaxAttempts()) {
      log.info("Agotados los intentos observando {} en {}; dejo de vigilar",
          watch.getIssueKey(), watch.getRepo());
      registry.remove(watch);
      return;
    }
    final Optional<JenkinsClient.Build> maybe = jenkinsClient.getLastBuild(watch.getJobPath());
    if (maybe.isEmpty()) {
      return; // la rama aún no está indexada en Jenkins
    }
    final JenkinsClient.Build build = maybe.get();
    if (!build.finished()) {
      return; // el build sigue corriendo
    }
    onFinished(watch, build);
    registry.remove(watch);
  }

  private void onFinished(final BuildWatch watch, final JenkinsClient.Build build) {
    if (build.success()) {
      jiraClient.addComment(watch.getIssueKey(), "✅ Build OK en " + watch.getRepo()
          + " (#" + build.number() + "): " + build.url());
      log.info("Build OK en {} para {}", watch.getRepo(), watch.getIssueKey());
      return;
    }
    final StringBuilder comment = new StringBuilder()
        .append("❌ Build ").append(build.result()).append(" en ").append(watch.getRepo())
        .append(" (#").append(build.number()).append("): ").append(build.url());
    final String diagnosis = diagnose(watch, build);
    if (diagnosis != null && !diagnosis.isBlank()) {
      comment.append("\n\nDiagnóstico de Sixai:\n").append(diagnosis);
    }
    jiraClient.addComment(watch.getIssueKey(), comment.toString());
    log.info("Build {} en {} para {}", build.result(), watch.getRepo(), watch.getIssueKey());
  }

  private String diagnose(final BuildWatch watch, final JenkinsClient.Build build) {
    if (!properties.isDiagnoseOnFailure() || !llmProperties.isEnabled()) {
      return null;
    }
    try {
      final String console = jenkinsClient.getConsoleLog(watch.getJobPath(), build.number());
      final String tail = tail(console, properties.getConsoleMaxChars());
      return llmClient.complete(LlmRequest.of(
          DIAGNOSE_SYSTEM_PROMPT, tail, LlmRoles.DIAGNOSE, watch.getIssueKey()));
    } catch (RuntimeException e) {
      log.warn("No se pudo diagnosticar el build de {}: {}", watch.getRepo(), e.getMessage());
      return null;
    }
  }

  private static String tail(final String value, final int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(value.length() - max);
  }
}

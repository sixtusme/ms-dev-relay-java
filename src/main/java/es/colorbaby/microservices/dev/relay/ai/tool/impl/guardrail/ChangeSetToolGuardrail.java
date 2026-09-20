package es.colorbaby.microservices.dev.relay.ai.tool.impl.guardrail;

import es.colorbaby.microservices.dev.relay.ai.agent.impl.ChangeSet;
import es.colorbaby.microservices.dev.relay.ai.agent.impl.FileChange;
import es.colorbaby.microservices.dev.relay.ai.tool.Tool;
import es.colorbaby.microservices.dev.relay.ai.tool.ToolGuardrail;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.guardrail.ChangeSetGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Aplica el guardarraíl de cambios de fichero ({@link ChangeSetGuard}) a CUALQUIER ejecución de
 * {@code github.commit}, sin importar qué agente la pida.
 *
 * <p>Antes de esta clase, {@link ChangeSetGuard} solo se invocaba a mano dentro de
 * {@code CoderService}: si mañana otro agente escribía ficheros, no había red de seguridad salvo
 * que alguien se acordara de llamarlo. Ahora es el {@code AgentRuntime} quien lo aplica de forma
 * genérica, así que el guardarraíl protege por diseño, no por disciplina.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChangeSetToolGuardrail implements ToolGuardrail {

  private static final String GITHUB_COMMIT = "github.commit";

  private final ChangeSetGuard changeSetGuard;

  @Override
  public boolean appliesTo(final Tool tool) {
    return GITHUB_COMMIT.equals(tool.name());
  }

  @Override
  public Outcome check(final ToolContext context, final ToolArguments arguments) {
    final Map<String, String> files = asStringMap(arguments.values().get("files"));
    if (files.isEmpty()) {
      return Outcome.deny("La tool " + GITHUB_COMMIT + " requiere el argumento 'files'");
    }

    final List<FileChange> proposed = files.entrySet().stream()
        .map(entry -> new FileChange(entry.getKey(), FileChange.ChangeType.UPDATE, entry.getValue()))
        .toList();
    final ChangeSetGuard.Result result = changeSetGuard.filter(new ChangeSet("", proposed));

    if (result.hasRejections()) {
      log.warn("Cambios bloqueados por el guardarraíl al ejecutar {} en {}: {}",
          GITHUB_COMMIT, context.issueKey(), result.rejected());
    }
    if (result.allowed().isEmpty()) {
      return Outcome.deny("Todos los cambios fueron bloqueados por el guardarraíl: "
          + result.rejected());
    }

    final Map<String, String> allowedFiles = new LinkedHashMap<>();
    for (final FileChange change : result.allowed().changes()) {
      allowedFiles.put(change.path(), change.content());
    }
    final Map<String, Object> newValues = new LinkedHashMap<>(arguments.values());
    newValues.put("files", allowedFiles);
    return Outcome.allow(new ToolArguments(newValues));
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> asStringMap(final Object value) {
    if (!(value instanceof Map<?, ?> raw)) {
      return Map.of();
    }
    final Map<String, String> result = new LinkedHashMap<>();
    for (final Map.Entry<?, ?> entry : raw.entrySet()) {
      result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
    }
    return result;
  }
}

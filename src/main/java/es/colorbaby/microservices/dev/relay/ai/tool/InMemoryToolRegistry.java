package es.colorbaby.microservices.dev.relay.ai.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Registro de tools respaldado por los beans {@link Tool} que Spring descubra en el contexto.
 *
 * <p>{@link #findForAgent} no filtra todavía por agente: hoy todas las tools registradas son de
 * solo lectura ({@code ToolRisk.READ}) y ningún {@link es.colorbaby.microservices.dev.relay.ai.agent.Agent}
 * declara aún qué tools necesita. Cuando existan tools de escritura y agentes concretos, esto debe
 * pasar a filtrar por una lista explícita de tool-ids por agente en vez de devolver todas.
 */
@Component
public class InMemoryToolRegistry implements ToolRegistry {

  private final Map<String, Tool> toolsByName;

  public InMemoryToolRegistry(final List<Tool> tools) {
    this.toolsByName = tools.stream()
        .collect(Collectors.toUnmodifiableMap(Tool::name, Function.identity()));
  }

  @Override
  public Optional<Tool> find(final String id) {
    return Optional.ofNullable(toolsByName.get(id));
  }

  @Override
  public List<Tool> findAll() {
    return List.copyOf(toolsByName.values());
  }

  @Override
  public List<Tool> findForAgent(final String agentId) {
    return findAll();
  }
}

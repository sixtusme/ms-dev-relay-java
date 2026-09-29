package es.colorbaby.microservices.dev.relay.ai.tool;

import java.util.List;
import java.util.Optional;

public interface ToolRegistry {

    Optional<Tool> find(String id);

    List<Tool> findAll();

    List<Tool> findForAgent(String agentId);
}

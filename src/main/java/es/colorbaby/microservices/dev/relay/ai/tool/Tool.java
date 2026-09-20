package es.colorbaby.microservices.dev.relay.ai.tool;

import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolSchema;
import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolRisk;

public interface Tool {
    String name();
    String description();
    ToolSchema inputSchema();
    ToolRisk risk();
    ToolResult execute(ToolContext context, ToolArguments arguments);
}

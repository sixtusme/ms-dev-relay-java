package es.colorbaby.microservices.dev.relay.ai.tool;

import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolArguments;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolContext;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolResult;
import es.colorbaby.microservices.dev.relay.ai.tool.record.ToolSchema;

public interface Tool {
    String name();
    String description();
    ToolSchema inputSchema();
    ToolResult execute(ToolContext context, ToolArguments arguments);
}

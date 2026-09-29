package es.colorbaby.microservices.dev.relay.ai.tool.record;

import es.colorbaby.microservices.dev.relay.ai.tool.state.ToolStatus;

public record ToolResult(
        ToolStatus status,
        String content,
        Object data
) {

    public static ToolResult success(String content, Object data) {
        return new ToolResult(
                ToolStatus.SUCCESS,
                content,
                data
        );
    }

    public static ToolResult failure(String content) {
        return new ToolResult(
                ToolStatus.FAILURE,
                content,
                null
        );
    }

    public static ToolResult denied(String content) {
        return new ToolResult(
                ToolStatus.DENIED,
                content,
                null
        );
    }
}

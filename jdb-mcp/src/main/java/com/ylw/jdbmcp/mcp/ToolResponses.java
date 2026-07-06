package com.ylw.jdbmcp.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/** Builds {@link McpSchema.CallToolResult} from DebugSession result maps. */
public final class ToolResponses {

    private ToolResponses() {}

    /** A map containing an "error" key becomes an MCP error result. */
    public static McpSchema.CallToolResult from(Map<String, Object> result) {
        String json = McpJson.toJson(result);
        boolean isError = result != null && result.containsKey("error");
        return McpSchema.CallToolResult.builder()
                .addTextContent(json)
                .isError(isError)
                .build();
    }

    public static McpSchema.CallToolResult error(String msg) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(msg)
                .isError(true)
                .build();
    }
}

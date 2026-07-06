package com.ylw.jdbmcp.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared Jackson mapper. Null fields are omitted to keep MCP responses compact. */
public final class McpJson {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private McpJson() {}

    public static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"error\":\"json serialization failed: " + e.getMessage() + "\"}";
        }
    }
}

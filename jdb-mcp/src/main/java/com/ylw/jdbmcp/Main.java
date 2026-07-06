package com.ylw.jdbmcp;

import com.ylw.jdbmcp.debug.DebugSession;
import com.ylw.jdbmcp.mcp.ToolDefs;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point. Loads jdb-mcp.json, builds the {@link DebugSession}, and exposes the tool
 * surface over MCP stdio.
 *
 * <p>Stdio is the MCP transport: protocol traffic flows over stdin/stdout. ALL logging and
 * target output goes to stderr - never stdout - to avoid corrupting the protocol.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        String cfgPath = System.getenv("JDB_MCP_CONFIG");
        if (cfgPath == null || cfgPath.isBlank()) cfgPath = "jdb-mcp.json";
        Config config = Config.load(Paths.get(cfgPath));

        // slf4j-simple → stderr only
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel",
                config.logLevel == null || config.logLevel.isBlank() ? "INFO" : config.logLevel);
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");

        DebugSession session = new DebugSession(config);

        StdioServerTransportProvider transport = new StdioServerTransportProvider();
        McpSchema.ServerCapabilities caps = McpSchema.ServerCapabilities.builder().tools(true).build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("jdb-mcp", "1.0.0")
                .capabilities(caps)
                .instructions(ToolDefs.USAGE_GUIDE)
                .tools(ToolDefs.build(session))
                .build();

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.close(); } catch (Exception ignored) {}
            try { session.stop(); } catch (Exception ignored) {}
            shutdown.countDown();
        }, "jdb-mcp-shutdown"));

        System.err.println("[jdb-mcp] MCP server ready on stdio (config=" + cfgPath + ")");
        System.err.println("[jdb-mcp] " + ToolDefs.ANTI_ADDICTION);
        shutdown.await();
    }
}

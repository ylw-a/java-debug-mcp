package com.ylw.jdbmcp;

import com.ylw.jdbmcp.debug.DebugSession;
import com.ylw.jdbmcp.mcp.ToolDefs;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.concurrent.CountDownLatch;

/**
 * 入口。零配置：不读任何配置文件。构建 {@link DebugSession} 并在 stdio 上暴露工具面。
 *
 * <p>stdio 是 MCP 传输层：协议流量走 stdin/stdout。所有日志与目标输出走 stderr，绝不走 stdout，
 * 以免污染协议。target 与可选 limits/budgets 通过 start_session 入参传入；会话进行中可用 configure 调整。
 *
 * <p>日志级别可用环境变量 JDB_MCP_LOG 覆盖（默认 INFO）。
 */
public class Main {

    public static void main(String[] args) throws Exception {
        String logLevel = System.getenv("JDB_MCP_LOG");
        if (logLevel == null || logLevel.isBlank()) logLevel = "INFO";

        // slf4j-simple -> stderr only
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", logLevel);
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");

        DebugSession session = new DebugSession();

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

        System.err.println("[jdb-mcp] MCP server ready on stdio (zero-config; pass target via start_session)");
        System.err.println("[jdb-mcp] " + ToolDefs.ANTI_ADDICTION);
        shutdown.await();
    }
}

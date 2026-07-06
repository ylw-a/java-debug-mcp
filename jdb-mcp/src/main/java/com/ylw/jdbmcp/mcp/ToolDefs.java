package com.ylw.jdbmcp.mcp;

import com.ylw.jdbmcp.debug.DebugSession;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Builds the MCP tool surface (10 tools) bound to a {@link DebugSession}.
 *
 * <p>Tool descriptions carry the safety / anti-addiction guidance: the AI is told, in the
 * descriptions and in the server instructions, to debug only with a concrete hypothesis and
 * to declare the data it needs up front rather than exploring speculatively.
 */
public final class ToolDefs {

    /**
     * Full usage guide, surfaced as the MCP server instructions (read once at session start).
     * Covers the workflow, expression grammar, modes, limits, and anti-addiction policy.
     */
    public static final String USAGE_GUIDE =
            "Java debugger exposed as MCP tools. Core idea: you PRE-DECLARE the values you want "
                    + "BEFORE the breakpoint fires; the program extracts exactly those at hit time and "
                    + "returns a lightweight structured snapshot. Full heap state is never dumped.\n"
                    + "\n"
                    + "WORKFLOW:\n"
                    + "1. start_session - launch (per config) or attach to the target JVM.\n"
                    + "2. set_breakpoint - give class + method/line, the captures (expressions), and mode A or B. "
                    + "Returns a breakpointId you use later.\n"
                    + "3. Retrieve results:\n"
                    + "   - Mode A (default, non-blocking): the bp fires asynchronously in the background. "
                    + "Call list_hits to poll snapshots - it may return 0 until the target reaches the code; "
                    + "retry after a moment. Hits accumulate (per-breakpoint ordinals, 1-based).\n"
                    + "   - Mode B (blocking): blocks until the hit, then the JVM STAYS SUSPENDED. Inspect via "
                    + "explore (read-path, budgeted) or eval (side-effects, off by default), then call resume.\n"
                    + "4. stop_session when done.\n"
                    + "\n"
                    + "EXPRESSION GRAMMAR (for captures and explore):\n"
                    + "  this                      the current object\n"
                    + "  this.field / obj.field    field read\n"
                    + "  a.b.c.d.e                 nested path (max 5 dereferences after the root)\n"
                    + "  obj.getX() / obj.isX()    no-arg getter (get*/is* prefixes, plus size/isEmpty/length/toString)\n"
                    + "  args[N]                   method parameter by position\n"
                    + "  arr[i] / list[i]          array index, or java.util.List index (uses get(int))\n"
                    + "The root is one of: this, args[N], or a local-variable name.\n"
                    + "If an intermediate segment is null you get status=null_break naming WHERE it broke "
                    + "(e.g. user.address.id with address=null -> null_break at 'address') - far more useful than a bare null.\n"
                    + "Limits: path depth 5, toString 500 chars, collection/array expanded to first 20 elements.\n"
                    + "\n"
                    + "CAPTURE vs EVAL: capture is read-only (fields, no-arg getters, indexing). eval invokes an "
                    + "arbitrary no-arg method and may have side effects (e.g. user.delete()); disabled by default, "
                    + "needs allowEval=true in config AND explicit user consent.\n"
                    + "\n"
                    + "CLASS NAMES: short/fuzzy names work (e.g. 'UserService'); Spring CGLIB/JDK proxies are "
                    + "filtered out by default (set include_proxies=true to hit them). If unsure of the real class, "
                    + "call list_classes first. A breakpoint on a not-yet-loaded class returns status=armed_deferred "
                    + "- this is normal; it materializes once the class loads.\n"
                    + "\n"
                    + "ANTI-ADDICTION: debugging is costly (target-JVM time + context). Use ONLY with a concrete "
                    + "hypothesis that logs, inspection, and tests cannot resolve. Declare your data, set ONE "
                    + "breakpoint, verify, and STOP. Do not debug speculatively or 'to understand'.";

    /** Short reminder reused in the most-used tool descriptions. */
    public static final String ANTI_ADDICTION =
            "DEBUGGING IS COSTLY. Use ONLY with a concrete hypothesis; declare your data, set ONE bp, verify, STOP.";

    private ToolDefs() {}

    public static List<McpServerFeatures.SyncToolSpecification> build(DebugSession session) {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        tools.add(tool("start_session",
                "Step 1 of the debug workflow. Launch the target JVM (per jdb-mcp.json config) under a JDWP agent "
                        + "and attach, OR pass attach:{host,port} to attach to an already-running JVM. Returns "
                        + "{status, pid, loadedClasses}. Must be called before any breakpoint tool.",
                "{\"type\":\"object\",\"properties\":{\"attach\":{\"type\":\"object\",\"properties\":{"
                        + "\"host\":{\"type\":\"string\"},\"port\":{\"type\":\"integer\"}},"
                        + "\"description\":\"If set, attach to a running JVM at host:port instead of launching from config.\"}},"
                        + "\"additionalProperties\":false}",
                (ex, a) -> {
                    Map<String, Object> attach = obj(a, "attach");
                    String host = attach != null ? str(attach, "host") : null;
                    Integer port = attach != null ? intArg(attach, "port") : null;
                    return ToolResponses.from(session.start(host, port));
                }));

        tools.add(tool("stop_session",
                "Detach from the target JVM and terminate it (if launched). Clears all breakpoints and hits.",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.stop())));

        tools.add(tool("session_status",
                "Report attachment state, armed breakpoints with hit counts, hit-buffer size, and remaining mode-B budgets. "
                        + "Use this to orient yourself before/after debugging.",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.sessionStatus())));

        tools.add(tool("list_classes",
                "Resolve a fuzzy/short class name to loaded classes (Spring proxies filtered out by default). "
                        + "Call this BEFORE set_breakpoint if you are unsure of the exact class - Spring beans are often "
                        + "CGLIB proxies or impl classes, not the interface name you have in mind.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"pattern\":{\"type\":\"string\",\"description\":\"fuzzy/short class name, e.g. UserService\"},"
                        + "\"include_proxies\":{\"type\":\"boolean\",\"default\":false}},"
                        + "\"required\":[\"pattern\"],\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.listClasses(str(a, "pattern"), bool(a, "include_proxies", false)))));

        tools.add(tool("set_breakpoint",
                "Step 2 of the debug workflow. Set a breakpoint and PRE-DECLARE the data to capture (see the "
                        + "expression grammar in server instructions). You must state exactly what values you want "
                        + "BEFORE the hit - data you forget to declare will NOT be captured. Returns "
                        + "{breakpointId, resolvedClass, status, ...}; use the breakpointId with list_hits.\n"
                        + " - mode A (default): status=armed (or armed_deferred if the class isn't loaded yet - "
                        + "normal, fires when it loads). Non-blocking; poll list_hits for snapshots (may be 0 until "
                        + "the target reaches the code - retry).\n"
                        + " - mode B: BLOCKS until the hit (status=hit), JVM stays suspended; then explore/eval, "
                        + "then resume. Prefer A unless you must inspect live state.\n"
                        + ANTI_ADDICTION,
                "{\"type\":\"object\",\"properties\":{"
                        + "\"class\":{\"type\":\"string\",\"description\":\"class name (short/fuzzy or fqcn)\"},"
                        + "\"method\":{\"type\":\"string\",\"description\":\"method name (sets bp at method entry)\"},"
                        + "\"line\":{\"type\":\"integer\",\"description\":\"line number\"},"
                        + "\"captures\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":"
                        + "\"pre-declared read-only expressions, e.g. this.id, user.address.id, args[0].name, user.getAddress().getId(), args[1].items[0].sku\"},"
                        + "\"mode\":{\"type\":\"string\",\"enum\":[\"A\",\"B\"],\"default\":\"A\"},"
                        + "\"include_proxies\":{\"type\":\"boolean\",\"default\":false},"
                        + "\"timeout\":{\"type\":\"integer\",\"description\":\"mode-B timeout in seconds (default 60)\"}},"
                        + "\"required\":[\"class\"],\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.setBreakpoint(
                        str(a, "class"), str(a, "method"), intArg(a, "line"),
                        strList(a, "captures"), str(a, "mode"),
                        bool(a, "include_proxies", false), intArg(a, "timeout")))));

        tools.add(tool("list_hits",
                "Retrieve captured snapshots for a breakpoint (the mode-A retrieval step), optionally limited to an "
                        + "ordinal range (e.g. from_hit=2, to_hit=4). Each hit has hit_id, breakpoint_id, class, method, "
                        + "line, thread, and the pre-declared captures with their status (ok / null_break / not_found / error). "
                        + "If count is 0 the breakpoint hasn't fired yet - wait and retry.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"breakpoint_id\":{\"type\":\"string\"},"
                        + "\"from_hit\":{\"type\":\"integer\",\"description\":\"1-based per-breakpoint ordinal (inclusive)\"},"
                        + "\"to_hit\":{\"type\":\"integer\",\"description\":\"1-based per-breakpoint ordinal (inclusive)\"}},"
                        + "\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.listHits(str(a, "breakpoint_id"), intArg(a, "from_hit"), intArg(a, "to_hit")))));

        tools.add(tool("remove_breakpoint",
                "Disable and remove a breakpoint.",
                "{\"type\":\"object\",\"properties\":{\"breakpoint_id\":{\"type\":\"string\"}},"
                        + "\"required\":[\"breakpoint_id\"],\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.removeBreakpoint(str(a, "breakpoint_id")))));

        tools.add(tool("resume",
                "Resume the target JVM after a mode-B hit (which leaves it suspended). Required before setting further "
                        + "breakpoints or letting the program proceed.",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.resume())));

        tools.add(tool("explore",
                "ESCAPE HATCH (mode B only, while JVM is suspended). Evaluate a read-only capture expression on the "
                        + "current frame for ad-hoc inspection. Strictly budgeted per suspend - do NOT use this as a "
                        + "substitute for pre-declared captures. If you find yourself exploring repeatedly, you failed "
                        + "to think through the captures: resume, re-arm with proper declarations.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"expr\":{\"type\":\"string\",\"description\":\"read-only expression, e.g. user.address.city\"}},"
                        + "\"required\":[\"expr\"],\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.explore(str(a, "expr")))));

        tools.add(tool("eval",
                "DANGEROUS (mode B only). Invoke an arbitrary no-arg method, e.g. user.delete() or cache.evict(). "
                        + "Disabled by default; requires allowEval=true in config AND explicit user consent for each call. "
                        + "Separate from capture by design: capture is read-only, eval can have side effects. " + ANTI_ADDICTION,
                "{\"type\":\"object\",\"properties\":{"
                        + "\"code\":{\"type\":\"string\",\"description\":\"no-arg method invocation, e.g. user.delete()\"}},"
                        + "\"required\":[\"code\"],\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.eval(str(a, "code")))));

        return tools;
    }

    private static McpServerFeatures.SyncToolSpecification tool(
            String name, String description, String inputSchemaJson,
            BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> handler) {
        McpSchema.Tool tool = new McpSchema.Tool(name, description, inputSchemaJson);
        return new McpServerFeatures.SyncToolSpecification(tool, handler);
    }

    // ---- arg extractors ----
    private static String str(Map<String, Object> a, String k) {
        if (a == null) return null;
        Object v = a.get(k);
        return v == null ? null : v.toString();
    }

    private static Integer intArg(Map<String, Object> a, String k) {
        if (a == null) return null;
        Object v = a.get(k);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    private static boolean bool(Map<String, Object> a, String k, boolean def) {
        if (a == null) return def;
        Object v = a.get(k);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> a, String k) {
        if (a == null) return null;
        Object v = a.get(k);
        if (v == null) return null;
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) out.add(o == null ? null : o.toString());
            return out;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Map<String, Object> a, String k) {
        if (a == null) return null;
        Object v = a.get(k);
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : null;
    }
}

package com.ylw.jdbmcp.mcp;

import com.ylw.jdbmcp.StartParams;
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
                    + "1. start_session - launch the target JVM (pass 'jar', or 'mainClass'+'classpath') or attach to a\n"
                    + "   running JVM (pass 'attach':{host,port}). ZERO-CONFIG: no file needed. Optional limits/budgets\n"
                    + "   overrides accepted. To discover a running JVM's port first, call list_debuggable_jvms.\n"
                    + "2. set_breakpoint - give class + method/line, the captures (expressions), and mode A or B. "
                    + "Returns a breakpointId you use later.\n"
                    + "3. Retrieve results:\n"
                    + "   - Mode A (default, non-blocking): the bp fires asynchronously in the background. "
                    + "Call list_hits to poll snapshots - it may return 0 until the target reaches the code; "
                    + "retry after a moment. Hits accumulate (per-breakpoint ordinals, 1-based).\n"
                    + "   - Mode B (blocking): blocks until the hit, then the JVM STAYS SUSPENDED. Inspect via "
                    + "explore (read-path, budgeted), get_frames/get_variables (full stack + locals), step (over/out/into), "
                    + "or eval (side-effects, off by default), then call resume.\n"
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
                    + "Limits: path depth 5, toString 500 chars, collection/array expanded to first 20 elements. "
                    + "These are conservative DEFAULTS (the recommended starting point, not a ceiling). If a capture "
                    + "returns depth_limit or truncation AND you have a concrete need, call configure to raise the "
                    + "specific limit - raises are logged and flagged. Lowering is always safe.\n"
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
                "Step 1 of the debug workflow. ZERO-CONFIG: pass target info directly as parameters. "
                        + "Launch mode: provide 'jar' (executable) or 'mainClass'+'classpath'. "
                        + "Attach mode: provide 'attach':{host,port}. "
                        + "All limits/budgets are optional (sensible defaults). Returns {status,pid,loadedClasses,limits}. "
                        + "Must be called before any breakpoint tool. "
                        + "Tip: if you don't know the target JVM's port, call list_debuggable_jvms first.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"jar\":{\"type\":\"string\",\"description\":\"Executable jar path (launch mode, uses -jar)\"},"
                        + "\"mainClass\":{\"type\":\"string\",\"description\":\"Main class FQCN (launch mode, with -cp)\"},"
                        + "\"classpath\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"Classpath entries for launch mode\"},"
                        + "\"jvmArgs\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"Extra JVM arguments\"},"
                        + "\"args\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"Program arguments\"},"
                        + "\"workingDir\":{\"type\":\"string\",\"description\":\"Working directory\"},"
                        + "\"suspend\":{\"type\":\"boolean\",\"default\":true,\"description\":\"Suspend target until debugger attaches\"},"
                        + "\"jdkPath\":{\"type\":\"string\",\"description\":\"JDK home (default: runtime JDK)\"},"
                        + "\"attach\":{\"type\":\"object\",\"properties\":{\"host\":{\"type\":\"string\"},\"port\":{\"type\":\"integer\"}},"
                        + "\"description\":\"Attach to a running JVM at host:port instead of launching\"},"
                        + "\"maxDepth\":{\"type\":\"integer\",\"description\":\"Max expression dereferences (default 5)\"},"
                        + "\"maxStrLen\":{\"type\":\"integer\",\"description\":\"Max toString/string chars (default 500)\"},"
                        + "\"maxCollSize\":{\"type\":\"integer\",\"description\":\"Max collection/array elements (default 20)\"},"
                        + "\"maxFields\":{\"type\":\"integer\",\"description\":\"Max object fields (default 50)\"},"
                        + "\"exploreBudget\":{\"type\":\"integer\",\"description\":\"Explore budget per mode-B suspend (default 5)\"},"
                        + "\"evalBudget\":{\"type\":\"integer\",\"description\":\"Eval budget per mode-B suspend (default 2)\"},"
                        + "\"stepBudget\":{\"type\":\"integer\",\"description\":\"Step budget per mode-B suspend (default 5)\"},"
                        + "\"modeBTimeoutSec\":{\"type\":\"integer\",\"description\":\"Mode-B timeout in seconds (default 60)\"},"
                        + "\"allowEval\":{\"type\":\"boolean\",\"description\":\"Enable the dangerous eval tool (default false)\"},"
                        + "\"safeMode\":{\"type\":\"boolean\",\"description\":\"Safe mode: no method invocation (default false)\"}},"
                        + "\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.start(startParams(a)))));

        tools.add(tool("configure",
                "Adjust session limits/budgets mid-session WITHOUT losing breakpoints or state. "
                        + "All fields optional - only pass what you need to change. "
                        + "Raising a limit ABOVE the default (e.g. maxDepth>5) logs a warning (anti-addiction: "
                        + "explicit overrides are tracked). Lowering is always silent. "
                        + "Returns the full effective configuration. "
                        + "Use this when a capture returns depth_limit/truncation AND you have a concrete, "
                        + "justified need for deeper inspection. " + ANTI_ADDICTION,
                "{\"type\":\"object\",\"properties\":{"
                        + "\"maxDepth\":{\"type\":\"integer\",\"description\":\"Max expression dereferences\"},"
                        + "\"maxStrLen\":{\"type\":\"integer\",\"description\":\"Max toString/string chars\"},"
                        + "\"maxCollSize\":{\"type\":\"integer\",\"description\":\"Max collection elements\"},"
                        + "\"maxFields\":{\"type\":\"integer\",\"description\":\"Max object fields\"},"
                        + "\"safeMode\":{\"type\":\"boolean\",\"description\":\"Safe mode: no method invocation (getters, toString, List size)\"},"
                        + "\"exploreBudget\":{\"type\":\"integer\",\"description\":\"Explore budget per mode-B suspend\"},"
                        + "\"evalBudget\":{\"type\":\"integer\",\"description\":\"Eval budget per mode-B suspend\"},"
                        + "\"stepBudget\":{\"type\":\"integer\",\"description\":\"Step budget per mode-B suspend\"},"
                        + "\"modeBTimeoutSec\":{\"type\":\"integer\",\"description\":\"Mode-B timeout in seconds\"},"
                        + "\"allowEval\":{\"type\":\"boolean\",\"description\":\"Enable the dangerous eval tool\"}},"
                        + "\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.configure(
                        intArg(a, "maxDepth"), intArg(a, "maxStrLen"), intArg(a, "maxCollSize"),
                        intArg(a, "maxFields"), boolArg(a, "safeMode"),
                        intArg(a, "exploreBudget"), intArg(a, "evalBudget"),
                        intArg(a, "stepBudget"), intArg(a, "modeBTimeoutSec"), boolArg(a, "allowEval")))));

        tools.add(tool("list_debuggable_jvms",
                "PRE-ATTACH DISCOVERY. Scan for running JVMs with JDWP debug agents that can be attached to. "
                        + "Runs jps -lv and parses -agentlib:jdwp lines. Returns processes with pid, mainClass, "
                        + "and jdwp:{server,address,port,attachable}. Use this BEFORE start_session when you need "
                        + "to attach to an already-running JVM but don't know the port. "
                        + "Does not require an attached session.",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.listDebuggableJvms())));

        tools.add(tool("stop_session",
                "Detach from the target JVM and terminate it (if launched). Clears all breakpoints and hits.",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.stop())));

        tools.add(tool("session_status",
                "Report attachment state, armed breakpoints with hit counts, hit-buffer size, and remaining mode-B budgets. "
                        + "Use this to orient yourself before/after debugging.",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.sessionStatus())));

        tools.add(tool("resolve_class",
                "Fuzzy-resolve a class name to confirm the exact FQCN before calling set_breakpoint. "
                        + "Returns candidate classes (with proxy/interface/prepared flags). "
                        + "If empty, the class isn't loaded yet - set_breakpoint will arm a deferred watcher. "
                        + "Use this when you're unsure about the exact class name (Spring beans are often wrapped "
                        + "in CGLIB proxies or impl classes).",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"pattern\":{\"type\":\"string\",\"description\":\"fuzzy/short class name, e.g. UserService\"},"
                        + "\"include_proxies\":{\"type\":\"boolean\",\"default\":false}},"
                        + "\"required\":[\"pattern\"],\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.resolveClass(str(a, "pattern"), bool(a, "include_proxies", false)))));

        tools.add(tool("list_classes",
                "Paginated enumeration of loaded classes (for orientation, not resolution). "
                        + "Use resolve_class to confirm a specific class name before set_breakpoint; "
                        + "use list_classes to browse what's loaded. Default limit 100, max 500.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"prefix\":{\"type\":\"string\",\"description\":\"Optional package prefix filter, e.g. com.example\"},"
                        + "\"include_proxies\":{\"type\":\"boolean\",\"default\":false},"
                        + "\"limit\":{\"type\":\"integer\",\"description\":\"Page size (default 100, max 500)\"},"
                        + "\"offset\":{\"type\":\"integer\",\"description\":\"Pagination offset (default 0)\"}},"
                        + "\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.listClasses(str(a, "prefix"), bool(a, "include_proxies", false),
                        intArg(a, "limit"), intArg(a, "offset")))));

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
                        + "\"timeout\":{\"type\":\"integer\",\"description\":\"mode-B timeout in seconds (default 60)\"},"
                        + "\"max_depth\":{\"type\":\"integer\",\"description\":\"Override session maxDepth for this bp only\"},"
                        + "\"max_str_len\":{\"type\":\"integer\",\"description\":\"Override session maxStrLen for this bp only\"},"
                        + "\"max_coll_size\":{\"type\":\"integer\",\"description\":\"Override session maxCollSize for this bp only\"},"
                        + "\"safe_mode\":{\"type\":\"boolean\",\"description\":\"Override session safeMode for this bp only\"}},"
                        + "\"required\":[\"class\"],\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.setBreakpoint(
                        str(a, "class"), str(a, "method"), intArg(a, "line"),
                        strList(a, "captures"), str(a, "mode"),
                        bool(a, "include_proxies", false), intArg(a, "timeout"),
                        intArg(a, "max_depth"), intArg(a, "max_str_len"), intArg(a, "max_coll_size"),
                        boolArg(a, "safe_mode")))));

        tools.add(tool("set_exception_breakpoint",
                "Set a breakpoint that fires when a specific exception is thrown (including subclasses). "
                        + "Captures use 'e' as the root to access the thrown exception (e.g. e.getMessage(), "
                        + "e.getCause().message, e.reason). Works with both mode A and B. "
                        + "The exception class is resolved via fuzzy matching (e.g. 'NullPointerException' or 'NPE'). "
                        + ANTI_ADDICTION,
                "{\"type\":\"object\",\"properties\":{"
                        + "\"exception_class\":{\"type\":\"string\",\"description\":\"Exception class name, e.g. NullPointerException or DemoException\"},"
                        + "\"caught\":{\"type\":\"boolean\",\"default\":true,\"description\":\"Fire on caught exceptions\"},"
                        + "\"uncaught\":{\"type\":\"boolean\",\"default\":true,\"description\":\"Fire on uncaught exceptions\"},"
                        + "\"captures\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
                        + "\"description\":\"pre-declared expressions (root 'e' = the exception), e.g. e.getMessage(), e.reason\"},"
                        + "\"mode\":{\"type\":\"string\",\"enum\":[\"A\",\"B\"],\"default\":\"A\"},"
                        + "\"include_proxies\":{\"type\":\"boolean\",\"default\":false},"
                        + "\"timeout\":{\"type\":\"integer\",\"description\":\"mode-B timeout in seconds (default 60)\"}},"
                        + "\"required\":[\"exception_class\"],\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.setExceptionBreakpoint(
                        str(a, "exception_class"), bool(a, "caught", true), bool(a, "uncaught", true),
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

        tools.add(tool("get_frames",
                "Mode B only. Get the full call stack of the suspended thread. Each frame includes "
                        + "index, className, method, line, and sourceFile. Passive inspection (no budget consumed). "
                        + "Use this to orient yourself when you hit a breakpoint - see where you are in the call chain.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"max_frames\":{\"type\":\"integer\",\"description\":\"Max frames to return (default 200)\"}},"
                        + "\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.getFrames(intArg(a, "max_frames")))));

        tools.add(tool("get_variables",
                "Mode B only. Get visible local variables for a specific stack frame, with optional deep "
                        + "expansion of selected paths. Returns each variable's name, type, and rendered value. "
                        + "If the target was compiled without -g:vars, returns a clear error suggesting args[N] "
                        + "instead. Passive inspection (no budget consumed). "
                        + "expand_paths: optional list of expressions to deep-evaluate on that frame (uses the "
                        + "same capture engine as set_breakpoint).",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"frame_index\":{\"type\":\"integer\",\"description\":\"Frame index (0=top, default 0)\"},"
                        + "\"expand_paths\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
                        + "\"description\":\"Expressions to deep-evaluate, e.g. ['this.address', 'args[0].name']\"}},"
                        + "\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.getVariables(intArg(a, "frame_index"), strList(a, "expand_paths")))));

        tools.add(tool("step",
                "Mode B only. Single-step the suspended thread one line over/out/into the current method. "
                        + "Step budget (default 5 per suspend) - DO NOT step through entire methods; use for "
                        + "short, focused path-tracing. Returns the new location {className, method, line, sourceFile}. "
                        + "The JVM stays suspended after the step so you can inspect variables or step again. "
                        + "createStepRequest parameter order: (thread, STEP_LINE, depth) - NOT (thread, depth, STEP_LINE)! "
                        + ANTI_ADDICTION,
                "{\"type\":\"object\",\"properties\":{"
                        + "\"kind\":{\"type\":\"string\",\"enum\":[\"over\",\"out\",\"into\"],"
                        + "\"default\":\"over\",\"description\":\"Step kind: over (next line), out (to caller), into (into method)\"}},"
                        + "\"additionalProperties\":false}",
                (ex, a) -> ToolResponses.from(session.step(str(a, "kind")))));

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
                        + "Disabled by default; requires allowEval=true (via start_session or configure) AND explicit user consent for each call. "
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
    private static StartParams startParams(Map<String, Object> a) {
        StartParams p = new StartParams();
        p.jar = str(a, "jar");
        p.mainClass = str(a, "mainClass");
        p.classpath = strList(a, "classpath");
        p.jvmArgs = strList(a, "jvmArgs");
        p.args = strList(a, "args");
        p.workingDir = str(a, "workingDir");
        p.suspend = bool(a, "suspend", true);
        p.jdkPath = str(a, "jdkPath");
        Map<String, Object> attach = obj(a, "attach");
        if (attach != null) {
            p.attachHost = str(attach, "host");
            p.attachPort = intArg(attach, "port");
        }
        p.maxDepth = intArg(a, "maxDepth");
        p.maxStrLen = intArg(a, "maxStrLen");
        p.maxCollSize = intArg(a, "maxCollSize");
        p.maxFields = intArg(a, "maxFields");
        p.exploreBudget = intArg(a, "exploreBudget");
        p.evalBudget = intArg(a, "evalBudget");
        p.stepBudget = intArg(a, "stepBudget");
        p.modeBTimeoutSec = intArg(a, "modeBTimeoutSec");
        p.allowEval = boolArg(a, "allowEval");
        p.safeMode = boolArg(a, "safeMode");
        return p;
    }

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

    private static Boolean boolArg(Map<String, Object> a, String k) {
        if (a == null) return null;
        Object v = a.get(k);
        if (v == null) return null;
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

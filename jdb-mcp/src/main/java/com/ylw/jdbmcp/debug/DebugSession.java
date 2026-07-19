package com.ylw.jdbmcp.debug;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.ylw.jdbmcp.Defaults;
import com.ylw.jdbmcp.StartParams;
import com.ylw.jdbmcp.snapshot.CaptureResult;
import com.ylw.jdbmcp.snapshot.Hit;
import com.ylw.jdbmcp.snapshot.HitBuffer;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 调试核心门面。MCP 工具层只与本对象对话。
 *
 * <p>持有 {@link VirtualMachine}、{@link BreakpointManager}、{@link HitBuffer}、{@link JdiEventLoop}
 * 与可变 {@link SessionConfig}，并执行 Mode-B 预算 / 防沉迷策略。所有 public 方法 synchronized：
 * 调试操作串行化，阻塞的 Mode-B 等待持锁直到命中返回（AI 后续 explore/resume 在 set_breakpoint
 * 返回后才发起，无自死锁）。
 *
 * <p>零配置：不再读配置文件。target 与可选 limits/budgets 通过 {@link StartParams} 传入；
 * {@link #configure} 可在会话进行中调整 limits/budgets（防沉迷破例入口，抬限留痕）。
 */
public class DebugSession {

    private static final int WARN_AFTER_DEBUG_ACTIONS = 8;
    private static final long CMD_TIMEOUT_SEC = 30;
    private static final int MAX_HITS = 1000;
    private static final String LAUNCH_HOST = "127.0.0.1";
    private static final int LAUNCH_PORT = 0; // 0 = target picks a free port

    private final SessionConfig sessionConfig = new SessionConfig();
    private final HitBuffer hits = new HitBuffer(MAX_HITS);

    private VirtualMachine vm;
    private BreakpointManager bpm;
    private JdiEventLoop eventLoop;
    private CommandBus commandBus;
    private TargetLauncher.LaunchedTarget launched;
    private boolean attached = false;

    // 每次挂起的 Mode-B 预算用量
    private int exploreUsed = 0;
    private int evalUsed = 0;
    private int stepUsed = 0;
    // 会话防沉迷计数
    private int debugActions = 0;

    public DebugSession() {}

    public boolean isAttached() {
        return attached;
    }

    public SessionConfig sessionConfig() {
        return sessionConfig;
    }

    // ---------------------------------------------------------------- session
    public synchronized Map<String, Object> start(StartParams params) {
        if (attached) {
            return err("already attached; call stop_session first");
        }
        if (params == null) {
            return err("start_session requires parameters: 'jar'/'mainClass' (launch) or 'attach' (attach mode)");
        }
        // 应用初始 limits/budgets 覆盖（null -> 默认）
        sessionConfig.applyStart(params);

        try {
            if (params.attachHost != null && !params.attachHost.isBlank()) {
                int port = params.attachPort == null ? 5005 : params.attachPort;
                vm = attach(params.attachHost, port);
                launched = null;
            } else {
                TargetSpec spec = new TargetSpec(
                        params.jar, params.mainClass, params.classpath,
                        params.jvmArgs, params.args, params.workingDir,
                        params.suspend, params.jdkPath);
                launched = TargetLauncher.launch(spec, LAUNCH_HOST, LAUNCH_PORT);
                vm = attach(LAUNCH_HOST, launched.port());
            }
        } catch (Exception e) {
            return err("failed to start target/attach: " + e.getMessage());
        }
        bpm = new BreakpointManager(vm);
        commandBus = new CommandBus();
        eventLoop = new JdiEventLoop(vm, bpm, hits, sessionConfig, commandBus);
        eventLoop.start();
        attached = true;

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "attached");
        res.put("mode", launched != null ? "launch" : "attach");
        if (launched != null) {
            res.put("pid", launched.process().pid());
        }
        res.put("loadedClasses", vm.allClasses().size());
        res.put("limits", limitsView());
        res.put("description", launched != null
                ? "target launched and suspended; set breakpoints then they fire on resume"
                : "attached to running target");
        return res;
    }

    public synchronized Map<String, Object> stop() {
        if (!attached) {
            return err("not attached");
        }
        // Explicitly delete all JDI event requests from the target VM before dispose, so a
        // subsequent re-attach to the same JVM doesn't inherit orphaned breakpoints.
        try { bpm.deleteAllRequests(); } catch (Throwable ignored) {}
        try {
            eventLoop.shutdown();
        } catch (Throwable ignored) {
        }
        try {
            vm.dispose();
        } catch (Throwable ignored) {
        }
        if (launched != null && launched.process().isAlive()) {
            launched.process().destroyForcibly();
        }
        attached = false;
        vm = null;
        bpm = null;
        eventLoop = null;
        commandBus = null;
        launched = null;
        exploreUsed = 0;
        evalUsed = 0;
        stepUsed = 0;
        debugActions = 0;
        hits.clear();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "stopped");
        return res;
    }

    private VirtualMachine attach(String host, int port) throws Exception {
        AttachingConnector conn = null;
        for (AttachingConnector c : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if ("com.sun.jdi.SocketAttach".equals(c.name())) {
                conn = c;
                break;
            }
        }
        if (conn == null) {
            throw new IllegalStateException("SocketAttach connector not available");
        }
        Map<String, Connector.Argument> args = conn.defaultArguments();
        args.get("hostname").setValue(host);
        args.get("port").setValue(String.valueOf(port));
        args.get("timeout").setValue("20000");
        return conn.attach(args);
    }

    // ---------------------------------------------------------------- configure
    public synchronized Map<String, Object> configure(Integer maxDepth, Integer maxStrLen, Integer maxCollSize,
                                                       Integer maxFields, Integer maxRenderNodes, Boolean safeMode,
                                                       Integer exploreBudget, Integer evalBudget,
                                                       Integer stepBudget, Integer modeBTimeoutSec, Boolean allowEval) {
        List<String> raised = sessionConfig.configure(maxDepth, maxStrLen, maxCollSize, maxFields, maxRenderNodes,
                safeMode, exploreBudget, evalBudget, stepBudget, modeBTimeoutSec, allowEval);
        if (!raised.isEmpty()) {
            debugActions++; // 显式破例：抬限高于默认，记一笔
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "configured");
        res.put("limits", limitsView());
        res.put("budgets", budgetsView());
        res.put("allowEval", sessionConfig.allowEval());
        if (!raised.isEmpty()) {
            res.put("warning", "limits raised above default (" + String.join(", ", raised)
                    + "); ensure this is justified by a concrete need. Defaults are conservative by design.");
        }
        warnIfHeavy(res);
        return res;
    }

    // ---------------------------------------------------------------- breakpoints
    public synchronized Map<String, Object> setBreakpoint(String cls, String method, Integer line,
                                                          List<String> captures, String mode,
                                                          boolean includeProxies, Integer timeoutSec,
                                                          Integer bpMaxDepth, Integer bpMaxStrLen,
                                                          Integer bpMaxCollSize, Boolean bpSafeMode) {
        if (!attached) {
            return err("not attached; call start_session first");
        }
        if (eventLoop.isSuspended()) {
            return err("target is suspended at a mode-B hit; call resume before setting another breakpoint");
        }
        if (cls == null || cls.isBlank()) {
            return err("class is required");
        }
        if (captures == null) captures = List.of();
        String m = mode == null || mode.isBlank() ? "A" : mode.toUpperCase();

        debugActions++;
        BreakpointManager.SetResult sr = bpm.setBreakpoint(cls, method, line, captures, includeProxies,
                bpMaxDepth, bpMaxStrLen, bpMaxCollSize, bpSafeMode);
        if (!sr.ok) {
            Map<String, Object> res = err(sr.error);
            if (sr.candidates != null) {
                res.put("candidates", sr.candidates);
            }
            return res;
        }

        if ("B".equals(m)) {
            // Mode B: NON-BLOCKING. Arm + mark modeB + start the target if suspended, then return.
            // The AI triggers its request (curl/manual), then calls resume to block until the hit.
            bpm.markModeB(sr.breakpointId);
            eventLoop.armModeB();
            try { vm.resume(); } catch (Throwable ignored) {} // start target if suspended at startup
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("mode", "B");
            res.put("breakpointId", sr.breakpointId);
            res.put("resolvedClass", sr.resolvedClass);
            if (sr.method != null) res.put("method", sr.method);
            if (sr.line != null) res.put("line", sr.line);
            res.put("captures", captures);
            res.put("hitCount", 0);
            res.put("status", sr.deferred ? "armed_deferred" : "armed");
            res.put("jvmSuspended", false);
            res.put("note", (sr.deferred
                    ? "class not loaded yet; armed via a class-prepare watcher. "
                    : "Breakpoint armed (NON-BLOCKING). ")
                    + "Trigger your request now (curl/manual), then call resume to block until the hit. "
                    + "JVM suspends at the hit for inspection (explore/get_variables/step); call resume again to continue.");
            warnIfHeavy(res);
            return res;
        }

        // Mode A：arm + resume，目标继续运行，断点在后台触发。
        try {
            vm.resume();
        } catch (Throwable ignored) {
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("mode", "A");
        res.put("breakpointId", sr.breakpointId);
        res.put("resolvedClass", sr.resolvedClass);
        if (sr.method != null) res.put("method", sr.method);
        if (sr.line != null) res.put("line", sr.line);
        res.put("captures", captures);
        res.put("hitCount", 0);
        res.put("status", sr.deferred ? "armed_deferred" : "armed");
        res.put("note", sr.deferred
                ? "class not loaded yet; armed via a class-prepare watcher and will fire once the class loads. Non-blocking - poll list_hits."
                : "non-blocking; hits are captured in the background. Call list_hits to retrieve snapshots.");
        warnIfHeavy(res);
        return res;
    }

    public synchronized Map<String, Object> setExceptionBreakpoint(String exceptionClass,
                                                         boolean caught, boolean uncaught,
                                                         List<String> captures, String mode,
                                                         boolean includeProxies, Integer timeoutSec) {
        if (!attached) return err("not attached; call start_session first");
        if (eventLoop.isSuspended()) {
            return err("target is suspended at a mode-B hit; call resume before setting another breakpoint");
        }
        if (exceptionClass == null || exceptionClass.isBlank()) return err("exception_class is required");
        if (captures == null) captures = List.of();
        String m = mode == null || mode.isBlank() ? "A" : mode.toUpperCase();

        debugActions++;
        BreakpointManager.SetResult sr = bpm.setExceptionBreakpoint(exceptionClass, caught, uncaught, captures, includeProxies);
        if (!sr.ok) {
            Map<String, Object> res = err(sr.error);
            if (sr.candidates != null) res.put("candidates", sr.candidates);
            return res;
        }

        if ("B".equals(m)) {
            // Mode B: NON-BLOCKING. Arm + mark modeB + start target, then return. resume blocks.
            bpm.markModeB(sr.breakpointId);
            eventLoop.armModeB();
            try { vm.resume(); } catch (Throwable ignored) {}
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("mode", "B");
            res.put("breakpointId", sr.breakpointId);
            res.put("exceptionClass", sr.resolvedClass);
            res.put("captures", captures);
            res.put("hitCount", 0);
            res.put("status", sr.deferred ? "armed_deferred" : "armed");
            res.put("jvmSuspended", false);
            res.put("note", (sr.deferred
                    ? "exception class not loaded yet; armed via a class-prepare watcher. "
                    : "Exception breakpoint armed (NON-BLOCKING). ")
                    + "Trigger your request, then call resume to block until the hit. Use 'e' as root in captures.");
            warnIfHeavy(res);
            return res;
        }

        try { vm.resume(); } catch (Throwable ignored) {}
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("mode", "A");
        res.put("breakpointId", sr.breakpointId);
        res.put("exceptionClass", sr.resolvedClass);
        res.put("captures", captures);
        res.put("hitCount", 0);
        res.put("status", sr.deferred ? "armed_deferred" : "armed");
        res.put("note", sr.deferred
                ? "exception class not loaded yet; armed via a class-prepare watcher."
                : "non-blocking; hits are captured in the background. Call list_hits. Use 'e' as root in captures.");
        warnIfHeavy(res);
        return res;
    }

    public synchronized Map<String, Object> removeBreakpoint(String id) {
        if (!attached) return err("not attached");
        if (id == null || !bpm.remove(id)) {
            return err("no such breakpoint: " + id);
        }
        // Clear this bp's captured hits so the buffer doesn't accumulate stale data
        // (keeps session_status hitBufferSize consistent with list_hits count).
        int cleared = hits.removeForBp(id);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "removed");
        res.put("breakpointId", id);
        res.put("clearedHits", cleared);
        return res;
    }

    public synchronized Map<String, Object> listBreakpoints() {
        if (!attached) return err("not attached");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Breakpoint bp : bpm.all()) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("breakpointId", bp.id);
            b.put("class", bp.resolvedClass);
            if (bp.methodName != null) b.put("method", bp.methodName);
            if (bp.line != null) b.put("line", bp.line);
            b.put("captures", bp.captures);
            b.put("hitCount", bp.hitCount);
            out.add(b);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("breakpoints", out);
        return res;
    }

    // ---------------------------------------------------------------- hits
    public synchronized Map<String, Object> listHits(String bpId, Integer from, Integer to,
                                                     Integer limit, Boolean compact) {
        if (!attached) return err("not attached");
        int lim = limit != null && limit > 0 ? Math.min(limit, Defaults.LIST_HITS_MAX) : Defaults.LIST_HITS_LIMIT;
        List<Hit> list = hits.query(bpId, from, to);
        boolean truncated = list.size() > lim;
        if (truncated) {
            // keep the most recent lim (query returns oldest-first)
            list = new ArrayList<>(list.subList(list.size() - lim, list.size()));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("count", list.size());
        res.put("truncated", truncated);
        if (compact != null && compact) {
            // Compact: metadata + capture expr/status only (omit ValueNode trees).
            List<Map<String, Object>> compactHits = new ArrayList<>();
            for (Hit h : list) {
                Map<String, Object> ch = new LinkedHashMap<>();
                ch.put("hitId", h.hitId);
                ch.put("hitOrdinal", h.hitOrdinal);
                ch.put("breakpointId", h.breakpointId);
                ch.put("class", h.className);
                ch.put("method", h.methodName);
                ch.put("line", h.line);
                ch.put("thread", h.threadName);
                ch.put("mode", h.mode);
                List<Map<String, Object>> caps = new ArrayList<>();
                for (CaptureResult cr : h.captures) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("expr", cr.expr);
                    c.put("status", cr.status);
                    if (cr.nullBreakAt != null) c.put("nullBreakAt", cr.nullBreakAt);
                    if (cr.error != null) c.put("error", cr.error);
                    caps.add(c);
                }
                ch.put("captures", caps);
                compactHits.add(ch);
            }
            res.put("hits", compactHits);
            res.put("compact", true);
            res.put("note", "compact view (capture values omitted). Re-call list_hits with from_hit/to_hit (no compact) for full values of a specific hit.");
        } else {
            res.put("hits", list);
            res.put("compact", false);
        }
        return res;
    }

    // ---------------------------------------------------------------- mode-B commands
    public synchronized Map<String, Object> explore(String expr) {
        if (!attached) return err("not attached");
        if (!eventLoop.isSuspended()) {
            return err("not suspended; explore is only valid during a mode-B hit. Call set_breakpoint with mode=B first.");
        }
        if (expr == null || expr.isBlank()) return err("expr is required");
        if (exploreUsed >= sessionConfig.exploreBudget()) {
            return err("explore budget exhausted (" + sessionConfig.exploreBudget()
                    + " per suspend). Call resume, then re-arm set_breakpoint with pre-declared captures.");
        }
        debugActions++;
        exploreUsed++;
        try {
            CompletableFuture<CaptureResult> f = new CompletableFuture<>();
            commandBus.put(new CommandBus.ExploreCmd(expr, f));
            CaptureResult r = f.get(CMD_TIMEOUT_SEC, TimeUnit.SECONDS);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("status", "ok");
            res.put("result", r);
            res.put("exploreBudgetRemaining", Math.max(0, sessionConfig.exploreBudget() - exploreUsed));
            warnIfHeavy(res);
            return res;
        } catch (Exception e) {
            return err("explore failed: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> eval(String code) {
        if (!attached) return err("not attached");
        if (!sessionConfig.allowEval()) {
            return err("eval is disabled. To enable: pass allowEval=true to start_session or configure, "
                    + "AND confirm explicit user consent for this specific call. Prefer capture (read-only).");
        }
        if (!eventLoop.isSuspended()) {
            return err("not suspended; eval is only valid during a mode-B hit.");
        }
        if (code == null || code.isBlank()) return err("code is required");
        if (evalUsed >= sessionConfig.evalBudget()) {
            return err("eval budget exhausted (" + sessionConfig.evalBudget() + " per suspend).");
        }
        debugActions++;
        evalUsed++;
        try {
            CompletableFuture<CaptureResult> f = new CompletableFuture<>();
            commandBus.put(new CommandBus.EvalCmd(code, f));
            CaptureResult r = f.get(CMD_TIMEOUT_SEC, TimeUnit.SECONDS);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("status", "ok");
            res.put("result", r);
            res.put("evalBudgetRemaining", Math.max(0, sessionConfig.evalBudget() - evalUsed));
            warnIfHeavy(res);
            return res;
        } catch (Exception e) {
            return err("eval failed: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> getFrames(Integer maxFrames) {
        if (!attached) return err("not attached");
        if (!eventLoop.isSuspended()) {
            return err("not suspended; get_frames is only valid during a mode-B hit.");
        }
        int max = maxFrames != null && maxFrames > 0 ? maxFrames : Defaults.MAX_FRAMES;
        try {
            CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
            commandBus.put(new CommandBus.GetFramesCmd(max, f));
            return f.get(CMD_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            return err("get_frames failed: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> getVariables(Integer frameIdx, List<String> expandPaths) {
        if (!attached) return err("not attached");
        if (!eventLoop.isSuspended()) {
            return err("not suspended; get_variables is only valid during a mode-B hit.");
        }
        int idx = frameIdx != null ? frameIdx : 0;
        try {
            CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
            commandBus.put(new CommandBus.GetVariablesCmd(idx, expandPaths, f));
            return f.get(CMD_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (Exception e) {
            return err("get_variables failed: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> step(String kind) {
        if (!attached) return err("not attached");
        if (!eventLoop.isSuspended()) {
            return err("not suspended; step is only valid during a mode-B hit.");
        }
        String k = kind == null || kind.isBlank() ? "over" : kind;
        if (!List.of("over", "out", "into").contains(k)) {
            return err("step kind must be 'over', 'out', or 'into'");
        }
        int budget = sessionConfig.stepBudget();
        if (stepUsed >= budget) {
            return err("step budget exhausted (" + budget + " per suspend).");
        }
        debugActions++;
        stepUsed++;
        try {
            CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
            commandBus.put(new CommandBus.StepCmd(k, f));
            Map<String, Object> r = f.get(CMD_TIMEOUT_SEC + 30, TimeUnit.SECONDS); // step may take longer
            r.put("stepBudgetRemaining", Math.max(0, budget - stepUsed));
            warnIfHeavy(r);
            return r;
        } catch (Exception e) {
            return err("step failed: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> resume() {
        if (!attached) return err("not attached");
        // Mode-B wait: set_breakpoint(mode=B) armed a non-blocking wait; block until the hit.
        if (eventLoop.isModeBPending()) {
            int timeout = sessionConfig.modeBTimeoutSec();
            exploreUsed = 0;
            evalUsed = 0;
            stepUsed = 0;
            Hit hit = eventLoop.waitForModeBHit(timeout);
            if (hit == null) {
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("error", "mode-B timed out after " + timeout
                        + "s (breakpoint stays armed; later hits go to the buffer - call list_hits). "
                        + "Re-arm with set_breakpoint(mode=B) and call resume to wait again.");
                return res;
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("status", "hit");
            res.put("mode", "B");
            res.put("breakpointId", hit.breakpointId);
            res.put("hit", hit);
            res.put("jvmSuspended", true);
            res.put("note", "JVM is suspended at the hit. Call explore/get_frames/get_variables/step (budgeted), then resume to continue.");
            res.put("exploreBudgetRemaining", Math.max(0, sessionConfig.exploreBudget() - exploreUsed));
            res.put("evalBudgetRemaining", Math.max(0, sessionConfig.evalBudget() - evalUsed));
            res.put("stepBudgetRemaining", sessionConfig.stepBudget());
            warnIfHeavy(res);
            return res;
        }
        // Continue mode: resume the suspended thread (after inspecting a mode-B hit).
        if (!eventLoop.isSuspended()) {
            return err("not suspended and no mode-B wait pending. To stop at a breakpoint: "
                    + "set_breakpoint(mode=B), trigger your request, then call resume to block until the hit.");
        }
        try {
            CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
            commandBus.put(new CommandBus.ResumeCmd(f));
            Map<String, Object> r = f.get(CMD_TIMEOUT_SEC, TimeUnit.SECONDS);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("status", "resumed");
            if (r != null) res.putAll(r);
            return res;
        } catch (Exception e) {
            return err("resume failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- list_debuggable_jvms
    /** Pre-attach discovery: list running JVMs with JDWP, marking attachable ones. */
    public Map<String, Object> listDebuggableJvms() {
        List<Map<String, Object>> results = new ArrayList<>();
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String jpsExe = javaHome + java.io.File.separator + "bin" + java.io.File.separator + "jps" + (windows ? ".exe" : "");
        java.io.File jpsFile = new java.io.File(jpsExe);
        if (!jpsFile.exists()) {
            return err("jps not found at " + jpsExe + " - ensure java.home points to a JDK (not JRE)");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(jpsExe, "-lv");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            for (String line : out.split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                Map<String, Object> entry = parseJpsLine(line);
                if (entry != null) results.add(entry);
            }
        } catch (Exception e) {
            return err("jps invocation failed: " + e.getMessage());
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("count", results.size());
        res.put("processes", results);
        return res;
    }

    private static Map<String, Object> parseJpsLine(String line) {
        // Format: <pid> [<mainClass>] [<args>...]
        // First token is pid, rest is mainClass + args (may be empty for jps itself)
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0) return null; // pid only, not interesting
        String pidStr = line.substring(0, firstSpace);
        long pid;
        try { pid = Long.parseLong(pidStr); } catch (NumberFormatException e) { return null; }
        String rest = line.substring(firstSpace + 1).trim();
        if (rest.isEmpty()) return null;

        // Parse jdwp args from the rest
        String mainClass;
        Map<String, String> jdwp = extractJdwp(rest);
        if (jdwp != null) {
            // The rest contains "-agentlib:jdwp=..."; strip it to get the main class + other args
            String strip = rest.replaceAll("-agentlib:jdwp\\s*=\\s*[^\\s]*\\s*", "").trim();
            // Also strip any other -X/-D options for mainClass extraction
            mainClass = strip.split("\\s+")[0];
            if (mainClass.startsWith("-")) mainClass = strip; // just flags, no clear main
        } else {
            // No jdwp; just use first token as mainClass
            mainClass = rest.split("\\s+")[0];
            if (mainClass.startsWith("-")) mainClass = rest;
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("pid", pid);
        entry.put("mainClass", mainClass);
        if (jdwp != null) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("server", jdwp.get("server"));
            j.put("address", jdwp.get("address"));
            String addr = jdwp.get("address");
            Integer port = null;
            if (addr != null) {
                int c = addr.lastIndexOf(':');
                String portStr = c >= 0 ? addr.substring(c + 1) : addr;
                try { port = Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}
            }
            j.put("port", port);
            boolean attachable = "y".equals(jdwp.get("server")) && port != null;
            j.put("attachable", attachable);
            entry.put("jdwp", j);
        }
        return entry;
    }

    private static Map<String, String> extractJdwp(String rest) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("-agentlib:jdwp\\s*=\\s*([^\\s]+)")
                .matcher(rest);
        if (!m.find()) return null;
        String jdwpArgs = m.group(1);
        Map<String, String> map = new LinkedHashMap<>();
        for (String part : jdwpArgs.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        // Only return if we found server/address (otherwise it's not a debuggable JVM)
        if (map.containsKey("server") || map.containsKey("address")) return map;
        return null;
    }

    // ---------------------------------------------------------------- status / resolve
    /** Fuzzy class resolution: confirm the exact class name before set_breakpoint. */
    public synchronized Map<String, Object> resolveClass(String pattern, boolean includeProxies) {
        if (!attached) return err("not attached");
        if (pattern == null || pattern.isBlank()) {
            return err("a class pattern is required (param 'pattern'; e.g. UserService). "
                    + "Note: 'class'/'name' are also accepted as aliases.");
        }
        List<ClassCandidate> cands = ClassResolver.resolve(vm, pattern, includeProxies);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("count", cands.size());
        res.put("candidates", cands);
        res.put("note", cands.isEmpty()
                ? "no loaded class matches '" + pattern + "'. The class may not be loaded yet - set_breakpoint will arm a deferred watcher."
                : cands.size() == 1 ? "exactly one match; use this class name in set_breakpoint"
                : "multiple matches; disambiguate by using the full qualified name from candidates");
        return res;
    }

    /** Paginated enumeration of loaded classes. */
    public synchronized Map<String, Object> listClasses(String prefix, boolean includeProxies, Integer limit, Integer offset) {
        if (!attached) return err("not attached");
        int lim = limit != null && limit > 0 ? limit : Defaults.LIST_CLASSES_LIMIT;
        int off = offset != null && offset >= 0 ? offset : 0;
        ClassResolver.EnumerateResult r = ClassResolver.enumerate(vm, prefix, includeProxies, lim, off);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("total", r.total);
        res.put("count", r.page.size());
        res.put("limit", lim);
        res.put("offset", off);
        res.put("candidates", r.page);
        return res;
    }

    public synchronized Map<String, Object> sessionStatus() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("attached", attached);
        res.put("suspended", attached && eventLoop != null && eventLoop.isSuspended());
        res.put("limits", limitsView());
        res.put("budgets", budgetsView());
        res.put("allowEval", sessionConfig.allowEval());
        if (attached) {
            List<Map<String, Object>> bps = new ArrayList<>();
            for (Breakpoint bp : bpm.all()) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("breakpointId", bp.id);
                b.put("class", bp.resolvedClass);
                b.put("hitCount", bp.hitCount);
                bps.add(b);
            }
            res.put("breakpoints", bps);
            res.put("hitBufferSize", hits.size());
            res.put("debugActions", debugActions);
            if (eventLoop.isSuspended()) {
                res.put("exploreBudgetRemaining", Math.max(0, sessionConfig.exploreBudget() - exploreUsed));
                res.put("evalBudgetRemaining", Math.max(0, sessionConfig.evalBudget() - evalUsed));
                res.put("stepBudgetRemaining", Math.max(0, sessionConfig.stepBudget()));
            }
        }
        return res;
    }

    // ---------------------------------------------------------------- helpers
    private Map<String, Object> limitsView() {
        ExprCapture.Limits l = sessionConfig.limits();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxDepth", l.pathDepth);
        m.put("maxStrLen", l.toStringLimit);
        m.put("maxCollSize", l.collectionLimit);
        m.put("maxFields", l.maxFields);
        m.put("maxRenderNodes", l.maxRenderNodes);
        return m;
    }

    private Map<String, Object> budgetsView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("explore", sessionConfig.exploreBudget());
        m.put("eval", sessionConfig.evalBudget());
        m.put("step", sessionConfig.stepBudget());
        m.put("modeBTimeoutSec", sessionConfig.modeBTimeoutSec());
        return m;
    }

    private void warnIfHeavy(Map<String, Object> res) {
        if (debugActions >= WARN_AFTER_DEBUG_ACTIONS) {
            res.put("warning", "Debug-action count this session: " + debugActions
                    + ". Debugging is costly - only continue if you still have a concrete, "
                    + "unresolved hypothesis. Prefer reasoning / logs / unit tests over more breakpoints.");
        }
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}

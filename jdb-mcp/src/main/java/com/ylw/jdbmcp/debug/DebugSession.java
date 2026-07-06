package com.ylw.jdbmcp.debug;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.ylw.jdbmcp.Config;
import com.ylw.jdbmcp.snapshot.CaptureResult;
import com.ylw.jdbmcp.snapshot.Hit;
import com.ylw.jdbmcp.snapshot.HitBuffer;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Facade over the whole debug core. The MCP tool layer talks only to this object.
 *
 * <p>Holds the {@link VirtualMachine}, {@link BreakpointManager}, {@link HitBuffer} and
 * {@link JdiEventLoop}, and enforces the mode-B budget / anti-addiction policy. All public
 * methods are synchronized: debug operations are serialized, and a blocking mode-B wait
 * holds the lock until the hit returns (the AI's subsequent explore/resume calls happen
 * only after set_breakpoint returns, so there is no self-deadlock).
 */
public class DebugSession {

    private static final int WARN_AFTER_DEBUG_ACTIONS = 8;
    private static final long CMD_TIMEOUT_SEC = 30;

    private final Config config;
    private final ExprCapture.Limits limits;
    private final HitBuffer hits;

    private VirtualMachine vm;
    private BreakpointManager bpm;
    private JdiEventLoop eventLoop;
    private CommandBus commandBus;
    private TargetLauncher.LaunchedTarget launched;
    private boolean attached = false;

    // per-suspend mode-B budgets
    private int exploreUsed = 0;
    private int evalUsed = 0;
    // session anti-addiction counter
    private int debugActions = 0;

    public DebugSession(Config config) {
        this.config = config;
        Config.DebugConfig d = config.debug;
        this.limits = new ExprCapture.Limits(d.captureDepth, d.captureDepth, d.toStringLimit, d.collectionLimit, 50);
        this.hits = new HitBuffer(d.maxHits);
    }

    public boolean isAttached() {
        return attached;
    }

    // ---------------------------------------------------------------- session
    public synchronized Map<String, Object> start(String attachHost, Integer attachPort) {
        if (attached) {
            return err("already attached; call stop_session first");
        }
        try {
            if (attachHost != null) {
                int port = attachPort == null ? 5005 : attachPort;
                vm = attach(attachHost, port);
                launched = null;
            } else {
                launched = TargetLauncher.launch(config.target, config.debug.host, config.debug.port);
                vm = attach(config.debug.host, launched.port());
            }
        } catch (Exception e) {
            return err("failed to start target/attach: " + e.getMessage());
        }
        bpm = new BreakpointManager(vm);
        commandBus = new CommandBus();
        eventLoop = new JdiEventLoop(vm, bpm, hits, limits, commandBus);
        eventLoop.start();
        attached = true;

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "attached");
        res.put("mode", launched != null ? "launch" : "attach");
        if (launched != null) {
            res.put("pid", launched.process().pid());
        }
        res.put("loadedClasses", vm.allClasses().size());
        res.put("description", launched != null
                ? "target launched and suspended; set breakpoints then they fire on resume"
                : "attached to running target");
        return res;
    }

    public synchronized Map<String, Object> stop() {
        if (!attached) {
            return err("not attached");
        }
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

    // ---------------------------------------------------------------- breakpoints
    public synchronized Map<String, Object> setBreakpoint(String cls, String method, Integer line,
                                                          List<String> captures, String mode,
                                                          boolean includeProxies, Integer timeoutSec) {
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
        BreakpointManager.SetResult sr = bpm.setBreakpoint(cls, method, line, captures, includeProxies);
        if (!sr.ok) {
            Map<String, Object> res = err(sr.error);
            if (sr.candidates != null) {
                res.put("candidates", sr.candidates);
            }
            return res;
        }

        if ("B".equals(m)) {
            return doModeB(sr, timeoutSec);
        }

        // Mode A: arm + resume so the target proceeds and the breakpoint can fire in the background.
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

    private Map<String, Object> doModeB(BreakpointManager.SetResult sr, Integer timeoutSec) {
        CountDownLatch latch = eventLoop.prepareModeBWait();
        exploreUsed = 0;
        evalUsed = 0;
        try {
            vm.resume();
        } catch (Throwable ignored) {
        }
        int timeout = timeoutSec != null && timeoutSec > 0 ? timeoutSec : config.debug.modeB.defaultTimeoutSec;
        boolean got;
        try {
            got = latch.await(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return err("mode-B wait interrupted");
        }
        Hit hit = eventLoop.claimSuspendedHitOrAbandon();
        if (hit == null) {
            Map<String, Object> res = err("mode-B timed out after " + timeout
                    + "s (breakpoint stays armed; later hits degrade to mode A - call list_hits)");
            res.put("breakpointId", sr.breakpointId);
            return res;
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "hit");
        res.put("mode", "B");
        res.put("breakpointId", sr.breakpointId);
        res.put("hit", hit);
        res.put("jvmSuspended", true);
        res.put("note", "JVM is suspended. You may call explore (read-path, limited) or resume to continue.");
        res.put("exploreBudgetRemaining", Math.max(0, config.debug.modeB.exploreBudget - exploreUsed));
        res.put("evalBudgetRemaining", Math.max(0, config.debug.modeB.evalBudget - evalUsed));
        warnIfHeavy(res);
        return res;
    }

    public synchronized Map<String, Object> removeBreakpoint(String id) {
        if (!attached) return err("not attached");
        if (id == null || !bpm.remove(id)) {
            return err("no such breakpoint: " + id);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "removed");
        res.put("breakpointId", id);
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
    public synchronized Map<String, Object> listHits(String bpId, Integer from, Integer to) {
        if (!attached) return err("not attached");
        List<Hit> list = hits.query(bpId, from, to);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("count", list.size());
        res.put("hits", list);
        return res;
    }

    // ---------------------------------------------------------------- mode-B commands
    public synchronized Map<String, Object> explore(String expr) {
        if (!attached) return err("not attached");
        if (!eventLoop.isSuspended()) {
            return err("not suspended; explore is only valid during a mode-B hit. Call set_breakpoint with mode=B first.");
        }
        if (expr == null || expr.isBlank()) return err("expr is required");
        if (exploreUsed >= config.debug.modeB.exploreBudget) {
            return err("explore budget exhausted (" + config.debug.modeB.exploreBudget
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
            res.put("exploreBudgetRemaining", Math.max(0, config.debug.modeB.exploreBudget - exploreUsed));
            warnIfHeavy(res);
            return res;
        } catch (Exception e) {
            return err("explore failed: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> eval(String code) {
        if (!attached) return err("not attached");
        if (!config.debug.allowEval) {
            return err("eval is disabled. To enable: set allowEval=true in jdb-mcp.json AND "
                    + "confirm explicit user consent for this specific call. Prefer capture (read-only).");
        }
        if (!eventLoop.isSuspended()) {
            return err("not suspended; eval is only valid during a mode-B hit.");
        }
        if (code == null || code.isBlank()) return err("code is required");
        if (evalUsed >= config.debug.modeB.evalBudget) {
            return err("eval budget exhausted (" + config.debug.modeB.evalBudget + " per suspend).");
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
            res.put("evalBudgetRemaining", Math.max(0, config.debug.modeB.evalBudget - evalUsed));
            warnIfHeavy(res);
            return res;
        } catch (Exception e) {
            return err("eval failed: " + e.getMessage());
        }
    }

    public synchronized Map<String, Object> resume() {
        if (!attached) return err("not attached");
        if (!eventLoop.isSuspended()) {
            return err("not suspended; nothing to resume");
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

    // ---------------------------------------------------------------- status / resolve
    public synchronized Map<String, Object> listClasses(String pattern, boolean includeProxies) {
        if (!attached) return err("not attached");
        List<ClassCandidate> cands = ClassResolver.resolve(vm, pattern, includeProxies);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("count", cands.size());
        res.put("candidates", cands);
        return res;
    }

    public synchronized Map<String, Object> sessionStatus() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("attached", attached);
        res.put("suspended", attached && eventLoop != null && eventLoop.isSuspended());
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
                res.put("exploreBudgetRemaining", Math.max(0, config.debug.modeB.exploreBudget - exploreUsed));
                res.put("evalBudgetRemaining", Math.max(0, config.debug.modeB.evalBudget - evalUsed));
            }
        }
        return res;
    }

    // ---------------------------------------------------------------- helpers
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

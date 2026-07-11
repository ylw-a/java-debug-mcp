package com.ylw.jdbmcp.debug;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;
import com.ylw.jdbmcp.snapshot.CaptureResult;
import com.ylw.jdbmcp.snapshot.Hit;
import com.ylw.jdbmcp.snapshot.HitBuffer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;

/**
 * JDI event loop thread. Owns ALL JDI Value operations - captures and eval happen here,
 * never on an MCP handler thread. Produces pure-POJO snapshots that handlers may read.
 *
 * <p>Two hit paths:
 * <ul>
 *   <li><b>Mode A</b> (no active mode-B waiter): capture → store in HitBuffer → resume thread.</li>
 *   <li><b>Mode B</b> (active waiter): capture → store → signal the waiting handler → enter a
 *       command loop that services explore/eval/resume on the suspended thread, then resume.</li>
 * </ul>
 *
 * <p>Concurrency: the mode-B decision, suspendedHit/suspendedThread assignment, and latch
 * countDown all happen atomically under {@link #modeBLock}. The handler's
 * {@link #claimSuspendedHitOrAbandon()} is under the same lock, so the post-await claim is
 * race-free: either the event loop committed the hit (claim returns it) or it didn't (claim
 * clears the waiter and later hits degrade to mode A).
 */
public class JdiEventLoop extends Thread {

    private final VirtualMachine vm;
    private final BreakpointManager bpm;
    private final HitBuffer hits;
    private final SessionConfig sessionConfig;
    private final CommandBus commandBus;

    private final Object modeBLock = new Object();
    private volatile CountDownLatch modeBLatch;
    private volatile Hit suspendedHit;
    private volatile ThreadReference suspendedThread;
    private volatile com.sun.jdi.Location suspendedLocation;
    private volatile boolean waiterActive = false;

    private volatile boolean running = true;
    private static final boolean debug = false;

    public JdiEventLoop(VirtualMachine vm, BreakpointManager bpm, HitBuffer hits,
                        SessionConfig sessionConfig, CommandBus commandBus) {
        this.vm = vm;
        this.bpm = bpm;
        this.hits = hits;
        this.sessionConfig = sessionConfig;
        this.commandBus = commandBus;
        setName("jdi-event-loop");
        setDaemon(true);
    }

    /** Arm a mode-B wait. Returns the latch the handler should await. */
    public CountDownLatch prepareModeBWait() {
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (modeBLock) {
            modeBLatch = latch;
            suspendedHit = null;
            suspendedThread = null;
            waiterActive = true;
        }
        return latch;
    }

    /**
     * Called by the handler after awaiting the latch. If the event loop committed a hit,
     * returns it (the event loop is now in its command loop - the AI may explore/resume).
     * Otherwise clears the waiter so later hits degrade to mode A and returns null (timeout).
     */
    public Hit claimSuspendedHitOrAbandon() {
        synchronized (modeBLock) {
            if (suspendedHit != null) {
                return suspendedHit;
            }
            waiterActive = false;
            return null;
        }
    }

    public boolean isSuspended() {
        synchronized (modeBLock) { return suspendedThread != null; }
    }

    public com.sun.jdi.Location suspendedLocation() {
        return suspendedLocation;
    }

    public CommandBus commandBus() {
        return commandBus;
    }

    @Override
    public void run() {
        try {
            while (running) {
                EventSet set = vm.eventQueue().remove();
                for (Event e : set) {
                    if (debug) System.err.println("[jdi] event: " + e.getClass().getSimpleName()
                            + " req=" + (e.request() == null ? "null" : e.request().getClass().getSimpleName()));
                    if (e instanceof BreakpointEvent be) {
                        handleBreakpoint(be);
                    } else if (e instanceof ExceptionEvent ee) {
                        handleException(ee);
                    } else if (e instanceof ClassPrepareEvent cpe) {
                        handleClassPrepare(cpe);
                    } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                        running = false;
                        CountDownLatch l = modeBLatch;
                        if (l != null) l.countDown();
                        break;
                    }
                }
            }
        } catch (InterruptedException ie) {
            // shutdown
        } catch (Throwable t) {
            System.err.println("[jdi] event loop terminated: " + t);
            t.printStackTrace();
        }
    }

    private void handleBreakpoint(BreakpointEvent be) {
        EventRequest req = be.request();
        Breakpoint bp = bpm.findByRequest(req);
        ThreadReference thread = be.thread();

        List<CaptureResult> snapshot = captureOn(thread, bp);
        int ordinal = bp == null ? 0 : ++bp.hitCount;
        Location loc = be.location();

        // Atomically decide mode B vs A and, if mode B, commit the suspended hit.
        boolean modeB;
        Hit stored;
        synchronized (modeBLock) {
            modeB = waiterActive;
            suspendedLocation = loc;
            String mode = modeB ? "B" : "A";
            Hit template = new Hit(0, ordinal,
                    bp == null ? "unknown" : bp.id,
                    loc.declaringType() != null ? loc.declaringType().name() : "?",
                    loc.method() != null ? loc.method().name() : null,
                    loc.lineNumber(),
                    thread.name(),
                    System.currentTimeMillis(),
                    mode,
                    snapshot);
            stored = hits.add(template);
            if (modeB) {
                suspendedThread = thread;
                suspendedHit = stored;
                CountDownLatch l = modeBLatch;
                if (l != null) l.countDown();
            }
        }

        if (modeB) {
            runCommandLoop(thread); // blocks until resume; thread stays suspended
        } else {
            try { thread.resume(); } catch (Throwable ignored) {}
        }
    }

    /** Exception breakpoint hit: capture with {@code e} root, then mode B/A routing. */
    private void handleException(ExceptionEvent ee) {
        EventRequest req = ee.request();
        Breakpoint bp = bpm.findByRequest(req);
        ThreadReference thread = ee.thread();
        com.sun.jdi.Value eObj = ee.exception();

        List<CaptureResult> snapshot = captureOnWithE(thread, bp, eObj);
        int ordinal = bp == null ? 0 : ++bp.hitCount;
        Location loc = ee.location();

        boolean modeB;
        synchronized (modeBLock) {
            modeB = waiterActive;
            suspendedLocation = loc;
            String mode = modeB ? "B" : "A";
            Hit template = new Hit(0, ordinal,
                    bp == null ? "unknown" : bp.id,
                    loc.declaringType() != null ? loc.declaringType().name() : "?",
                    loc.method() != null ? loc.method().name() : null,
                    loc.lineNumber(),
                    thread.name(),
                    System.currentTimeMillis(),
                    mode,
                    snapshot);
            hits.add(template);
            if (modeB) {
                suspendedThread = thread;
                suspendedHit = template;
                CountDownLatch l = modeBLatch;
                if (l != null) l.countDown();
            }
        }

        if (modeB) {
            runCommandLoop(thread);
        } else {
            try { thread.resume(); } catch (Throwable ignored) {}
        }
    }

    private List<CaptureResult> captureOnWithE(ThreadReference thread, Breakpoint bp, com.sun.jdi.Value eRoot) {
        List<CaptureResult> out = new ArrayList<>();
        if (bp == null || bp.captures.isEmpty()) return out;
        ExprCapture.Limits eff = bp.effectiveOver(sessionConfig.limits());
        for (String expr : bp.captures) {
            StackFrame frame;
            try {
                frame = thread.frame(0);
            } catch (Throwable t) {
                out.add(CaptureResult.error(expr, "could not get top frame: " + t.getMessage()));
                continue;
            }
            try {
                out.add(ExprCapture.evaluate(thread, frame, expr, eff, eRoot));
            } catch (Throwable t) {
                out.add(CaptureResult.error(expr, t.getMessage()));
            }
        }
        return out;
    }

    /** A deferred breakpoint's class just loaded: materialize the breakpoint, then resume. */
    private void handleClassPrepare(ClassPrepareEvent cpe) {
        Breakpoint bp = bpm.findByRequest(cpe.request());
        if (bp != null) {
            ReferenceType rt = cpe.referenceType();
            boolean armed = bpm.materialize(bp, rt);
            if (armed) {
                System.err.println("[jdi] materialized deferred breakpoint " + bp.id + " on " + rt.name());
            }
        }
        // The ClassPrepareRequest used SUSPEND_EVENT_THREAD; resume so the target proceeds.
        try { vm.resume(); } catch (Throwable ignored) {}
    }

    private List<CaptureResult> captureOn(ThreadReference thread, Breakpoint bp) {
        List<CaptureResult> out = new ArrayList<>();
        if (bp == null || bp.captures.isEmpty()) return out;
        ExprCapture.Limits eff = bp.effectiveOver(sessionConfig.limits());
        for (String expr : bp.captures) {
            StackFrame frame;
            try {
                frame = thread.frame(0);
            } catch (Throwable t) {
                out.add(CaptureResult.error(expr, "could not get top frame: " + t.getMessage()));
                continue;
            }
            try {
                out.add(ExprCapture.evaluate(thread, frame, expr, eff));
            } catch (Throwable t) {
                out.add(CaptureResult.error(expr, t.getMessage()));
            }
        }
        return out;
    }

    private void runCommandLoop(ThreadReference thread) {
        try {
            while (running) {
                CommandBus.Command cmd = commandBus.take();
                if (cmd instanceof CommandBus.ResumeCmd rc) {
                    try { thread.resume(); } catch (Throwable ignored) {}
                    synchronized (modeBLock) {
                        suspendedThread = null;
                        suspendedHit = null;
                        waiterActive = false;
                    }
                    rc.future().complete(Map.of("resumed", true, "thread", thread.name()));
                    return;
                } else if (cmd instanceof CommandBus.ExploreCmd ec) {
                    try {
                        StackFrame f = thread.frame(0);
                        ec.future().complete(ExprCapture.evaluate(thread, f, ec.expr(), sessionConfig.limits()));
                    } catch (Throwable t) {
                        ec.future().complete(CaptureResult.error(ec.expr(), "explore failed: " + t.getMessage()));
                    }
                } else if (cmd instanceof CommandBus.EvalCmd ev) {
                    try {
                        StackFrame f = thread.frame(0);
                        ev.future().complete(ExprEval.evaluate(thread, f, ev.code(), sessionConfig.limits()));
                    } catch (Throwable t) {
                        ev.future().complete(CaptureResult.error(ev.code(), "eval failed: " + t.getMessage()));
                    }
                } else if (cmd instanceof CommandBus.GetFramesCmd gc) {
                    try {
                        List<StackFrame> frames = thread.frames();
                        int max = Math.min(gc.maxFrames(), frames.size());
                        List<Map<String, Object>> flat = new ArrayList<>();
                        for (int i = 0; i < max; i++) {
                            StackFrame sf = frames.get(i);
                            Map<String, Object> f = new LinkedHashMap<>();
                            f.put("index", i);
                            f.put("className", sf.location().declaringType() != null
                                    ? sf.location().declaringType().name() : "?");
                            f.put("method", sf.location().method() != null
                                    ? sf.location().method().name() : "?");
                            f.put("line", sf.location().lineNumber());
                            try {
                                f.put("sourceFile", sf.location().sourceName());
                            } catch (Throwable ignored) {
                                f.put("sourceFile", null);
                            }
                            flat.add(f);
                        }
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("count", flat.size());
                        r.put("frames", flat);
                        gc.future().complete(r);
                    } catch (Throwable t) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("error", "get_frames failed: " + t.getMessage());
                        gc.future().complete(r);
                    }
                } else if (cmd instanceof CommandBus.GetVariablesCmd gv) {
                    try {
                        StackFrame frame = thread.frame(gv.frameIdx());
                        List<Map<String, Object>> vars = new ArrayList<>();
                        try {
                            for (com.sun.jdi.LocalVariable lv : frame.visibleVariables()) {
                                Map<String, Object> v = new LinkedHashMap<>();
                                v.put("name", lv.name());
                                v.put("type", lv.typeName());
                                try {
                                    com.sun.jdi.Value val = frame.getValue(lv);
                                    v.put("value", ExprCapture.renderValue(
                                            val, sessionConfig.limits(), thread));
                                } catch (Throwable t2) {
                                    v.put("value", com.ylw.jdbmcp.snapshot.ValueNode.nullVal("?"));
                                }
                                vars.add(v);
                            }
                        } catch (com.sun.jdi.AbsentInformationException aie) {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("error", "no debug info: compile target with -g:vars for named locals; use args[N] in capture for positional params");
                            gv.future().complete(r);
                            continue;
                        }
                        // expand_paths
                        List<Map<String, Object>> expanded = new ArrayList<>();
                        if (gv.expandPaths() != null) {
                            for (String path : gv.expandPaths()) {
                                try {
                                    CaptureResult cr = ExprCapture.evaluate(thread, frame, path, sessionConfig.limits());
                                    Map<String, Object> e = new LinkedHashMap<>();
                                    e.put("expr", path);
                                    e.put("result", cr);
                                    expanded.add(e);
                                } catch (Throwable ignored) {
                                    Map<String, Object> e = new LinkedHashMap<>();
                                    e.put("expr", path);
                                    e.put("error", "evaluation failed: " + ignored.getMessage());
                                    expanded.add(e);
                                }
                            }
                        }
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("frameIndex", gv.frameIdx());
                        r.put("variables", vars);
                        r.put("expanded", expanded);
                        gv.future().complete(r);
                    } catch (Throwable t) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("error", "get_variables failed: " + t.getMessage());
                        gv.future().complete(r);
                    }
                } else if (cmd instanceof CommandBus.StepCmd sc) {
                    doStep(thread, sc);
                }
            }
        } catch (InterruptedException ie) {
            // shutdown
        } finally {
            synchronized (modeBLock) {
                suspendedThread = null;
                suspendedHit = null;
                waiterActive = false;
            }
        }
    }

    /**
     * 单步执行（step over / out / into）。嵌套事件泵：创建 StepRequest → resume → 等待 StepEvent
     * → 返回新位置。途中遇 BreakpointEvent 则 capture+存 hit+resume 继续。StepRequest 用完即弃。
     *
     * <p><b>参数顺序陷阱</b>：{@link com.sun.jdi.request.EventRequestManager#createStepRequest}
     * 的签名是 (ThreadReference thread, int size, int depth)。第二参是 size（STEP_LINE），第三参是
     * depth（STEP_OVER/STEP_OUT/STEP_INTO）——常有人把 depth/size 搞反。此处用具名常量避免。
     */
    private void doStep(ThreadReference thread, CommandBus.StepCmd sc) {
        int depth = switch (sc.kind()) {
            case "out" -> com.sun.jdi.request.StepRequest.STEP_OUT;
            case "into" -> com.sun.jdi.request.StepRequest.STEP_INTO;
            default -> com.sun.jdi.request.StepRequest.STEP_OVER;
        };
        com.sun.jdi.request.StepRequest req = null;
        try {
            req = vm.eventRequestManager().createStepRequest(
                    thread, com.sun.jdi.request.StepRequest.STEP_LINE, depth);
            req.setSuspendPolicy(com.sun.jdi.request.StepRequest.SUSPEND_EVENT_THREAD);
            req.enable();
            thread.resume();
        } catch (Throwable t) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("error", "step request creation failed: " + t.getMessage());
            sc.future().complete(r);
            return;
        }

        // 嵌套事件泵：等待 StepEvent，途中处理其他事件
        try {
            while (running) {
                com.sun.jdi.event.EventSet set = vm.eventQueue().remove();
                for (com.sun.jdi.event.Event e : set) {
                    if (e instanceof com.sun.jdi.event.StepEvent se) {
                        com.sun.jdi.Location loc = se.location();
                        suspendedLocation = loc;
                        // 删除自禁用的 StepRequest（避免泄漏）
                        try { vm.eventRequestManager().deleteEventRequest(se.request()); } catch (Throwable ignored) {}
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("status", "stepped");
                        r.put("kind", sc.kind());
                        r.put("className", loc.declaringType() != null ? loc.declaringType().name() : "?");
                        r.put("method", loc.method() != null ? loc.method().name() : "?");
                        r.put("line", loc.lineNumber());
                        try { r.put("sourceFile", loc.sourceName()); } catch (Throwable ignored) { r.put("sourceFile", null); }
                        sc.future().complete(r);
                        return;
                    } else if (e instanceof com.sun.jdi.event.BreakpointEvent be) {
                        // 途中遇到断点：capture + 存 hit（mode A 降级），然后 resume 继续等 step
                        handleBreakpoint(be);
                        try { vm.resume(); } catch (Throwable ignored) {}
                    } else if (e instanceof com.sun.jdi.event.VMDeathEvent || e instanceof com.sun.jdi.event.VMDisconnectEvent) {
                        running = false;
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("error", "VM disconnected during step");
                        sc.future().complete(r);
                        return;
                    }
                }
            }
        } catch (InterruptedException ie) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("error", "step interrupted");
            sc.future().complete(r);
        } catch (Throwable t) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("error", "step failed: " + t.getMessage());
            sc.future().complete(r);
        }
    }

    public void shutdown() {
        running = false;
        this.interrupt();
        commandBus.clear();
    }
}

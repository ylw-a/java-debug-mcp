package com.ylw.jdbmcp.debug;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;
import com.ylw.jdbmcp.snapshot.CaptureResult;
import com.ylw.jdbmcp.snapshot.Hit;
import com.ylw.jdbmcp.snapshot.HitBuffer;

import java.util.ArrayList;
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
    private final ExprCapture.Limits limits;
    private final CommandBus commandBus;

    private final Object modeBLock = new Object();
    private volatile CountDownLatch modeBLatch;
    private volatile Hit suspendedHit;
    private volatile ThreadReference suspendedThread;
    private volatile boolean waiterActive = false;

    private volatile boolean running = true;
    private static final boolean debug = false;

    public JdiEventLoop(VirtualMachine vm, BreakpointManager bpm, HitBuffer hits,
                        ExprCapture.Limits limits, CommandBus commandBus) {
        this.vm = vm;
        this.bpm = bpm;
        this.hits = hits;
        this.limits = limits;
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
        for (String expr : bp.captures) {
            // Re-fetch the top frame per expression: invokeMethod (getters/toString)
            // invalidates the previous StackFrame handle, so a cached frame would throw.
            StackFrame frame;
            try {
                frame = thread.frame(0);
            } catch (Throwable t) {
                out.add(CaptureResult.error(expr, "could not get top frame: " + t.getMessage()));
                continue;
            }
            try {
                out.add(ExprCapture.evaluate(thread, frame, expr, limits));
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
                        ec.future().complete(ExprCapture.evaluate(thread, f, ec.expr(), limits));
                    } catch (Throwable t) {
                        ec.future().complete(CaptureResult.error(ec.expr(), "explore failed: " + t.getMessage()));
                    }
                } else if (cmd instanceof CommandBus.EvalCmd ev) {
                    try {
                        StackFrame f = thread.frame(0);
                        ev.future().complete(ExprEval.evaluate(thread, f, ev.code(), limits));
                    } catch (Throwable t) {
                        ev.future().complete(CaptureResult.error(ev.code(), "eval failed: " + t.getMessage()));
                    }
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

    public void shutdown() {
        running = false;
        this.interrupt();
        commandBus.clear();
    }
}

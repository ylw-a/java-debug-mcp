package com.ylw.jdbmcp.debug;

import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.ExceptionRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages breakpoint lifecycle: resolve class → locate → arm JDI requests → track hits.
 *
 * <p>Class resolution uses {@link ClassResolver}: a fuzzy short name maps to one concrete
 * prepared class; if multiple remain (after proxy filtering), the caller is handed the
 * candidate list to disambiguate. Breakpoints suspend only the event thread.
 */
public class BreakpointManager {

    private final VirtualMachine vm;
    private final Map<String, Breakpoint> bps = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);

    public BreakpointManager(VirtualMachine vm) {
        this.vm = vm;
    }

    public static final class SetResult {
        public final boolean ok;
        public final String breakpointId;
        public final String resolvedClass;
        public final String method;
        public final Integer line;
        public final String error;
        public final List<ClassCandidate> candidates;
        /** True when the class wasn't loaded yet and a deferred watcher was armed. */
        public boolean deferred;

        private SetResult(boolean ok, String id, String cls, String m, Integer line,
                          String err, List<ClassCandidate> cands) {
            this.ok = ok; this.breakpointId = id; this.resolvedClass = cls;
            this.method = m; this.line = line; this.error = err; this.candidates = cands;
        }
        static SetResult ok(String id, String cls, String m, Integer line) {
            return new SetResult(true, id, cls, m, line, null, null);
        }
        static SetResult error(String msg) {
            return new SetResult(false, null, null, null, null, msg, null);
        }
        static SetResult ambiguous(List<ClassCandidate> c) {
            return new SetResult(false, null, null, null, null,
                    "multiple classes match; specify one fqcn from candidates", c);
        }
    }

    public SetResult setBreakpoint(String classPattern, String methodName, Integer line,
                                   List<String> captures, boolean includeProxies,
                                   Integer bpMaxDepth, Integer bpMaxStrLen,
                                   Integer bpMaxCollSize, Boolean bpSafeMode) {
        ReferenceType rt = ClassResolver.pickOne(vm, classPattern, includeProxies);
        if (rt == null) {
            List<ClassCandidate> cands = ClassResolver.resolve(vm, classPattern, includeProxies);
            if (!cands.isEmpty()) {
                return SetResult.ambiguous(cands);
            }
            // Class not loaded yet: arm a deferred breakpoint via ClassPrepareRequest.
            return armDeferred(classPattern, methodName, line, captures, includeProxies,
                    bpMaxDepth, bpMaxStrLen, bpMaxCollSize, bpSafeMode);
        }

        List<Location> locs = findLocations(rt, methodName, line);
        if (locs.isEmpty()) {
            return SetResult.error("no executable location for class=" + rt.name()
                    + (methodName != null ? " method=" + methodName : "")
                    + (line != null ? " line=" + line : "")
                    + " (provide method and/or line)");
        }

        List<BreakpointRequest> reqs = new ArrayList<>();
        for (Location loc : locs) {
            try {
                BreakpointRequest req = vm.eventRequestManager().createBreakpointRequest(loc);
                req.setSuspendPolicy(BreakpointRequest.SUSPEND_EVENT_THREAD);
                req.enable();
                reqs.add(req);
            } catch (Throwable t) {
                // best-effort: keep whatever we could create
            }
        }
        if (reqs.isEmpty()) {
            return SetResult.error("failed to create breakpoint request at " + locs.get(0));
        }

        String id = "bp-" + idGen.getAndIncrement();
        Breakpoint bp = new Breakpoint(id, rt.name(), methodName, line, captures, includeProxies);
        bp.resolvedClass = rt.name();
        bp.bpMaxDepth = bpMaxDepth;
        bp.bpMaxStrLen = bpMaxStrLen;
        bp.bpMaxCollSize = bpMaxCollSize;
        bp.bpSafeMode = bpSafeMode;
        bp.requests.addAll(reqs);
        bp.locations.addAll(locs);
        bp.status = "armed";
        bps.put(id, bp);

        Integer lineVal = line != null ? line : (locs.isEmpty() ? null : locs.get(0).lineNumber());
        return SetResult.ok(id, rt.name(), methodName, lineVal);
    }

    /** Deferred breakpoint: the class isn't loaded yet. Watch for it to prepare, then materialize. */
    private SetResult armDeferred(String classPattern, String methodName, Integer line,
                                  List<String> captures, boolean includeProxies,
                                  Integer bpMaxDepth, Integer bpMaxStrLen,
                                  Integer bpMaxCollSize, Boolean bpSafeMode) {
        String id = "bp-" + idGen.getAndIncrement();
        Breakpoint bp = new Breakpoint(id, classPattern, methodName, line, captures, includeProxies);
        bp.resolvedClass = classPattern + " (pending load)";
        bp.status = "deferred";
        bp.bpMaxDepth = bpMaxDepth;
        bp.bpMaxStrLen = bpMaxStrLen;
        bp.bpMaxCollSize = bpMaxCollSize;
        bp.bpSafeMode = bpSafeMode;
        try {
            ClassPrepareRequest cpr = vm.eventRequestManager().createClassPrepareRequest();
            // JDI classFilter wildcards match package prefixes only (e.g. *.Foo, com.example.*),
            // not arbitrary substrings. For a short name use "*.Name" (that class in any package);
            // for a qualified name use it directly.
            String filter = classPattern.contains(".") ? classPattern : "*." + classPattern;
            cpr.addClassFilter(filter);
            cpr.setSuspendPolicy(ClassPrepareRequest.SUSPEND_EVENT_THREAD);
            cpr.enable();
            bp.classPrepareRequest = cpr;
        } catch (Throwable t) {
            return SetResult.error("class not loaded and could not arm deferred watcher for '"
                    + classPattern + "': " + t.getMessage());
        }
        bps.put(id, bp);

        Integer lineVal = line;
        SetResult r = SetResult.ok(id, classPattern, methodName, lineVal);
        r.deferred = true;
        return r;
    }

    /**
     * Materialize a deferred breakpoint on a freshly-prepared class. Returns true if the
     * breakpoint was armed (method/line found); false to keep waiting for another matching class.
     */
    public boolean materialize(Breakpoint bp, ReferenceType rt) {
        if (bp == null || !"deferred".equals(bp.status)) return false;
        if (!includeProxiesOk(bp, rt)) return false;

        if ("exception".equals(bp.type)) {
            // Materialize deferred exception breakpoint
            try {
                ExceptionRequest req = vm.eventRequestManager().createExceptionRequest(rt, true, true);
                req.setSuspendPolicy(ExceptionRequest.SUSPEND_EVENT_THREAD);
                req.enable();
                bp.requests.add(req);
                bp.resolvedClass = rt.name();
                bp.status = "armed";
                if (bp.classPrepareRequest != null) {
                    try { vm.eventRequestManager().deleteEventRequest(bp.classPrepareRequest); }
                    catch (Throwable ignored) {}
                    bp.classPrepareRequest = null;
                }
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        List<Location> locs = findLocations(rt, bp.methodName, bp.line);
        if (locs.isEmpty()) return false; // this class doesn't have the method/line; keep waiting

        List<BreakpointRequest> reqs = new ArrayList<>();
        for (Location loc : locs) {
            try {
                BreakpointRequest req = vm.eventRequestManager().createBreakpointRequest(loc);
                req.setSuspendPolicy(BreakpointRequest.SUSPEND_EVENT_THREAD);
                req.enable();
                reqs.add(req);
            } catch (Throwable ignored) {}
        }
        if (reqs.isEmpty()) return false;

        bp.requests.addAll(reqs);
        bp.locations.addAll(locs);
        bp.resolvedClass = rt.name();
        bp.status = "armed";
        // Stop watching once armed.
        if (bp.classPrepareRequest != null) {
            try { vm.eventRequestManager().deleteEventRequest(bp.classPrepareRequest); }
            catch (Throwable ignored) {}
            bp.classPrepareRequest = null;
        }
        return true;
    }

    private boolean includeProxiesOk(Breakpoint bp, ReferenceType rt) {
        if (bp.includeProxies) return true;
        return !ProxyFilter.isProxy(rt);
    }

    private List<Location> findLocations(ReferenceType rt, String methodName, Integer line) {
        List<Location> locs = new ArrayList<>();
        try {
            if (line != null) {
                locs.addAll(rt.locationsOfLine(line));
            } else if (methodName != null) {
                for (Method m : rt.methodsByName(methodName)) {
                    Location l = m.location();
                    if (l != null) locs.add(l);
                }
            }
        } catch (Throwable ignored) {
        }
        return locs;
    }

    public Breakpoint get(String id) {
        return bps.get(id);
    }

    /** Find the breakpoint whose JDI request fired (breakpoint or class-prepare watcher). */
    public Breakpoint findByRequest(EventRequest req) {
        if (req == null) return null;
        for (Breakpoint bp : bps.values()) {
            if (bp.requests.contains(req)) return bp;
            if (bp.classPrepareRequest == req) return bp;
        }
        return null;
    }

    /** Set an exception breakpoint. Captures use {@code e} as the root for the thrown exception. */
    public SetResult setExceptionBreakpoint(String exceptionClass, boolean notifyCaught, boolean notifyUncaught,
                                            List<String> captures, boolean includeProxies) {
        ReferenceType rt = ClassResolver.pickOne(vm, exceptionClass, includeProxies);
        if (rt == null) {
            // Exception class not loaded yet; arm a deferred watcher.
            return armDeferredException(exceptionClass, notifyCaught, notifyUncaught, captures, includeProxies);
        }

        ExceptionRequest req;
        try {
            req = vm.eventRequestManager().createExceptionRequest(rt, notifyCaught, notifyUncaught);
            req.setSuspendPolicy(ExceptionRequest.SUSPEND_EVENT_THREAD);
            req.enable();
        } catch (Throwable t) {
            return SetResult.error("failed to create exception request for " + rt.name() + ": " + t.getMessage());
        }

        String id = "bp-" + idGen.getAndIncrement();
        Breakpoint bp = new Breakpoint(id, rt.name(), null, null, captures, includeProxies);
        bp.resolvedClass = rt.name();
        bp.type = "exception";
        bp.requests.add(req);
        bp.status = "armed";
        bps.put(id, bp);

        return SetResult.ok(id, rt.name(), null, null);
    }

    private SetResult armDeferredException(String exceptionClass, boolean notifyCaught, boolean notifyUncaught,
                                           List<String> captures, boolean includeProxies) {
        String id = "bp-" + idGen.getAndIncrement();
        Breakpoint bp = new Breakpoint(id, exceptionClass, null, null, captures, includeProxies);
        bp.resolvedClass = exceptionClass + " (pending load)";
        bp.status = "deferred";
        bp.type = "exception";
        try {
            ClassPrepareRequest cpr = vm.eventRequestManager().createClassPrepareRequest();
            String filter = exceptionClass.contains(".") ? exceptionClass : "*." + exceptionClass;
            cpr.addClassFilter(filter);
            cpr.setSuspendPolicy(ClassPrepareRequest.SUSPEND_EVENT_THREAD);
            cpr.enable();
            bp.classPrepareRequest = cpr;
        } catch (Throwable t) {
            return SetResult.error("exception class not loaded and could not arm deferred watcher for '"
                    + exceptionClass + "': " + t.getMessage());
        }
        bps.put(id, bp);
        SetResult r = SetResult.ok(id, exceptionClass, null, null);
        r.deferred = true;
        return r;
    }

    public boolean remove(String id) {
        Breakpoint bp = bps.remove(id);
        if (bp == null) return false;
        deleteRequests(bp);
        return true;
    }

    /** Delete all JDI requests (breakpoint + class-prepare) for one bp. */
    private void deleteRequests(Breakpoint bp) {
        try {
            if (!bp.requests.isEmpty()) vm.eventRequestManager().deleteEventRequests(bp.requests);
        } catch (Throwable ignored) {}
        if (bp.classPrepareRequest != null) {
            try { vm.eventRequestManager().deleteEventRequest(bp.classPrepareRequest); }
            catch (Throwable ignored) {}
        }
        bp.requests.clear();
        bp.classPrepareRequest = null;
    }

    /** Delete ALL JDI requests for ALL breakpoints (called on stop_session before dispose). */
    public void deleteAllRequests() {
        for (Breakpoint bp : bps.values()) {
            deleteRequests(bp);
        }
    }

    /** Mark a breakpoint as mode B (interactive). Returns false if no such bp. */
    public boolean markModeB(String id) {
        Breakpoint bp = bps.get(id);
        if (bp == null) return false;
        bp.modeB = true;
        return true;
    }

    public Collection<Breakpoint> all() {
        return bps.values();
    }
}

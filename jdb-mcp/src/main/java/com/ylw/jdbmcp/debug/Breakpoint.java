package com.ylw.jdbmcp.debug;

import com.sun.jdi.Location;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;

import java.util.ArrayList;
import java.util.List;

/** Internal breakpoint record: AI-supplied intent + JDI request handles. */
public class Breakpoint {

    public final String id;
    public final String classFqcn;
    public final String methodName;     // nullable
    public final Integer line;          // nullable
    public final List<String> captures;
    public final boolean includeProxies;

    /** Resolved concrete class (after ClassResolver). */
    public String resolvedClass;
    public int hitCount = 0;
    public final List<EventRequest> requests = new ArrayList<>();
    public final List<Location> locations = new ArrayList<>();

    /** "line" = code breakpoint, "exception" = exception breakpoint. */
    public String type = "line";
    /** "armed" = breakpoint active; "deferred" = waiting for the class to load. */
    public String status = "armed";
    /** Watcher set when the class isn't loaded yet; nulled once materialized. */
    public ClassPrepareRequest classPrepareRequest;

    /** True if set as mode B (interactive: resume blocks for the next hit of this bp). One-shot:
     *  consumed (set false) once the hit is claimed by a resume-wait; later hits degrade to mode A. */
    public boolean modeB;

    // ---- per-breakpoint capture overrides (null = inherit session) ----
    public Integer bpMaxDepth;
    public Integer bpMaxStrLen;
    public Integer bpMaxCollSize;
    public Boolean bpSafeMode;

    /** Synthesize effective limits from session base + per-breakpoint overrides. */
    public ExprCapture.Limits effectiveOver(ExprCapture.Limits base) {
        if (bpMaxDepth == null && bpMaxStrLen == null && bpMaxCollSize == null && bpSafeMode == null) return base;
        int depth = bpMaxDepth != null ? bpMaxDepth : base.pathDepth;
        return new ExprCapture.Limits(
                depth, depth,
                bpMaxStrLen != null ? bpMaxStrLen : base.toStringLimit,
                bpMaxCollSize != null ? bpMaxCollSize : base.collectionLimit,
                base.maxFields,
                bpSafeMode != null ? bpSafeMode : base.safeMode,
                base.maxRenderNodes);
    }

    public Breakpoint(String id, String classFqcn, String methodName, Integer line,
                      List<String> captures, boolean includeProxies) {
        this.id = id;
        this.classFqcn = classFqcn;
        this.methodName = methodName;
        this.line = line;
        this.captures = captures == null ? List.of() : List.copyOf(captures);
        this.includeProxies = includeProxies;
    }
}

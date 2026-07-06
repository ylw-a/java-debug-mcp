package com.ylw.jdbmcp.debug;

import com.sun.jdi.Location;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;

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
    public final List<BreakpointRequest> requests = new ArrayList<>();
    public final List<Location> locations = new ArrayList<>();

    /** "armed" = breakpoint active; "deferred" = waiting for the class to load. */
    public String status = "armed";
    /** Watcher set when the class isn't loaded yet; nulled once materialized. */
    public ClassPrepareRequest classPrepareRequest;

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

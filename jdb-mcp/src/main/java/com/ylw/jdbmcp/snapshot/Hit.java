package com.ylw.jdbmcp.snapshot;

import java.util.List;

/** One breakpoint hit, fully materialized as POJOs (safe to pass across threads). */
public class Hit {

    /** Global monotonic id (stable across the whole session). */
    public final long hitId;
    /** Per-breakpoint ordinal, 1-based (the Nth time this breakpoint fired). */
    public final int hitOrdinal;
    public final String breakpointId;
    public final String className;
    public final String methodName;
    public final int line;
    public final String threadName;
    public final long timestampMs;
    public final String mode;
    public final List<CaptureResult> captures;

    public Hit(long hitId, int hitOrdinal, String breakpointId, String className,
               String methodName, int line, String threadName, long timestampMs,
               String mode, List<CaptureResult> captures) {
        this.hitId = hitId;
        this.hitOrdinal = hitOrdinal;
        this.breakpointId = breakpointId;
        this.className = className;
        this.methodName = methodName;
        this.line = line;
        this.threadName = threadName;
        this.timestampMs = timestampMs;
        this.mode = mode;
        this.captures = captures;
    }
}

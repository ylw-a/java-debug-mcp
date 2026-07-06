package com.ylw.jdbmcp.snapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded ring buffer of {@link Hit}. Capacity-limited with roll-off: once full,
 * the oldest hit is discarded. The JDI event thread writes; MCP handler threads read.
 *
 * <p>Queries are by breakpoint id and per-breakpoint ordinal range, so the AI can ask
 * for "hits 3..5 of breakpoint X" rather than only the most recent.
 */
public class HitBuffer {

    private final int capacity;
    private final Deque<Hit> ring = new ArrayDeque<>();
    private long nextHitId = 1;

    public HitBuffer(int capacity) {
        this.capacity = capacity <= 0 ? 1000 : capacity;
    }

    /** Assigns a global hitId and appends; evicts the oldest if over capacity. */
    public synchronized Hit add(Hit template) {
        long id = nextHitId++;
        Hit stored = new Hit(id, template.hitOrdinal, template.breakpointId, template.className,
                template.methodName, template.line, template.threadName, template.timestampMs,
                template.mode, template.captures);
        ring.addLast(stored);
        while (ring.size() > capacity) {
            ring.pollFirst();
        }
        return stored;
    }

    /** Hits of a single breakpoint whose ordinal is in [fromOrdinal, toOrdinal] (inclusive). */
    public synchronized List<Hit> query(String breakpointId, Integer fromOrdinal, Integer toOrdinal) {
        int from = fromOrdinal == null ? 1 : fromOrdinal;
        int to = toOrdinal == null ? Integer.MAX_VALUE : toOrdinal;
        List<Hit> out = new ArrayList<>();
        for (Hit h : ring) {
            if (breakpointId != null && !breakpointId.equals(h.breakpointId)) continue;
            if (h.hitOrdinal < from || h.hitOrdinal > to) continue;
            out.add(h);
        }
        return out;
    }

    public synchronized Hit getById(long hitId) {
        for (Hit h : ring) {
            if (h.hitId == hitId) return h;
        }
        return null;
    }

    public synchronized int size() {
        return ring.size();
    }

    public synchronized void clear() {
        ring.clear();
        nextHitId = 1;
    }
}

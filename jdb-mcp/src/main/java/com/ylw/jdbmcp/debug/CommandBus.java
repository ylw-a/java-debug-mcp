package com.ylw.jdbmcp.debug;

import com.ylw.jdbmcp.snapshot.CaptureResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Delivers mode-B commands (explore / eval / resume) from an MCP handler thread to the
 * JDI event loop thread, which is the only thread allowed to touch JDI Value objects.
 * Each command carries a future the handler awaits.
 */
public final class CommandBus {

    private final BlockingQueue<Command> queue = new LinkedBlockingQueue<>();

    public void put(Command c) {
        try {
            queue.put(c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public Command take() throws InterruptedException {
        return queue.take();
    }

    public void clear() {
        queue.clear();
    }

    sealed interface Command permits ExploreCmd, EvalCmd, ResumeCmd, GetFramesCmd, GetVariablesCmd, StepCmd {}
    record ExploreCmd(String expr, CompletableFuture<CaptureResult> future) implements Command {}
    record EvalCmd(String code, CompletableFuture<CaptureResult> future) implements Command {}
    record ResumeCmd(CompletableFuture<Map<String, Object>> future) implements Command {}
    record GetFramesCmd(int maxFrames, CompletableFuture<Map<String, Object>> future) implements Command {}
    record GetVariablesCmd(int frameIdx, List<String> expandPaths, CompletableFuture<Map<String, Object>> future) implements Command {}
    record StepCmd(String kind, CompletableFuture<Map<String, Object>> future) implements Command {}
}

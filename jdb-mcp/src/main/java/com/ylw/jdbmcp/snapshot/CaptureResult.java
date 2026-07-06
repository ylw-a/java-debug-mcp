package com.ylw.jdbmcp.snapshot;

/**
 * Outcome of evaluating one pre-declared capture expression at a hit.
 *
 * <p>Status semantics:
 * <ul>
 *   <li>{@code ok}        - fully resolved; {@code value} is populated.</li>
 *   <li>{@code null_break}- an intermediate segment was null; {@code nullBreakAt} names it.
 *       Knowing <em>where</em> the path broke is far more valuable than a bare null.</li>
 *   <li>{@code not_found} - a field/method/variable named in the path does not exist.</li>
 *   <li>{@code error}     - evaluation threw; {@code error} holds the message.</li>
 *   <li>{@code depth_limit}- path exceeded the configured depth cap.</li>
 * </ul>
 */
public class CaptureResult {

    public String expr;
    public String status;
    public String nullBreakAt;
    public String error;
    public ValueNode value;

    public static CaptureResult ok(String expr, ValueNode value) {
        CaptureResult r = new CaptureResult();
        r.expr = expr;
        r.status = "ok";
        r.value = value;
        return r;
    }

    public static CaptureResult nullBreak(String expr, String at) {
        CaptureResult r = new CaptureResult();
        r.expr = expr;
        r.status = "null_break";
        r.nullBreakAt = at;
        return r;
    }

    public static CaptureResult notFound(String expr, String what) {
        CaptureResult r = new CaptureResult();
        r.expr = expr;
        r.status = "not_found";
        r.error = what;
        return r;
    }

    public static CaptureResult error(String expr, String msg) {
        CaptureResult r = new CaptureResult();
        r.expr = expr;
        r.status = "error";
        r.error = msg;
        return r;
    }

    public static CaptureResult depthLimit(String expr, String msg) {
        CaptureResult r = new CaptureResult();
        r.expr = expr;
        r.status = "depth_limit";
        r.error = msg;
        return r;
    }
}

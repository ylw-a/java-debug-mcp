package com.ylw.jdbmcp.debug;

import com.sun.jdi.*;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ylw.jdbmcp.snapshot.CaptureResult;
import com.ylw.jdbmcp.snapshot.ValueNode;

/**
 * Mode-B escape hatch: invoke an arbitrary no-arg method on a reachable value.
 *
 * <p>Reuses {@link ExprCapture#resolveValue} to obtain the receiver, then invokes a
 * method by name - without the read-only getter restriction. This is the channel for
 * methods with side effects (e.g. {@code cache.evict()}, {@code user.delete()}).
 *
 * <p><b>Strictly limited</b>: off by default (config {@code allowEval}), per-call budget,
 * and the tool description forces the AI to confirm user consent. Capture (read-only) is
 * always preferred; eval exists only for the cases capture cannot cover.
 */
public final class ExprEval {

    private static final Pattern METHOD_ON_PATH = Pattern.compile("^(.*)\\.(\\w+)\\(\\)\\s*$");
    private static final Pattern BARE_METHOD = Pattern.compile("^(\\w+)\\(\\)\\s*$");

    private ExprEval() {}

    /**
     * @param code expression like {@code user.delete()} or {@code flush()}
     * @return a capture result describing the invocation outcome / return value
     */
    public static CaptureResult evaluate(ThreadReference thread, StackFrame frame, String code, ExprCapture.Limits limits) {
        String receiverExpr;
        String methodName;
        Matcher m = METHOD_ON_PATH.matcher(code.trim());
        if (m.matches()) {
            receiverExpr = m.group(1).trim();
            methodName = m.group(2);
        } else {
            Matcher m2 = BARE_METHOD.matcher(code.trim());
            if (m2.matches()) {
                receiverExpr = "this";
                methodName = m2.group(1);
            } else {
                return CaptureResult.error(code, "eval expression must be '<path>.<method>()' or '<method>()' (no-arg only)");
            }
        }

        ExprCapture.Resolution r = ExprCapture.resolveValue(thread, frame, receiverExpr, limits);
        return switch (r.status) {
            case "ok" -> {
                if (r.value == null) {
                    yield CaptureResult.nullBreak(code, receiverExpr + " (cannot invoke " + methodName + "() on null)");
                }
                if (!(r.value instanceof ObjectReference obj)) {
                    yield CaptureResult.error(code, "cannot invoke " + methodName + "() on non-object ("
                            + (r.value == null ? "null" : r.value.type().name()) + ")");
                }
                yield invokeAndRender(thread, code, obj, methodName, limits);
            }
            case "null_break" -> CaptureResult.nullBreak(code, r.breakAt);
            case "not_found" -> CaptureResult.notFound(code, r.message);
            case "depth_limit" -> CaptureResult.depthLimit(code, r.message);
            default -> CaptureResult.error(code, r.message);
        };
    }

    private static CaptureResult invokeAndRender(ThreadReference thread, String code,
                                                 ObjectReference obj, String methodName, ExprCapture.Limits limits) {
        Method target = null;
        for (Method m : obj.referenceType().methodsByName(methodName)) {
            if (m.isStatic()) continue;
            if (!m.argumentTypeNames().isEmpty()) continue;
            target = m;
            break;
        }
        if (target == null) {
            return CaptureResult.notFound(code, "no no-arg method '" + methodName + "()' on " + obj.referenceType().name());
        }

        boolean isVoid = "void".equals(target.returnTypeName());
        Value result;
        try {
            result = obj.invokeMethod(thread, target, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (InvocationException ie) {
            return CaptureResult.error(code, methodName + "() threw: " + ie.exception().referenceType().name());
        } catch (IncompatibleThreadStateException | InvalidTypeException | ClassNotLoadedException e) {
            return CaptureResult.error(code, methodName + "() invocation failed: " + e.getMessage());
        } catch (Throwable t) {
            return CaptureResult.error(code, methodName + "() invocation failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        if (isVoid) {
            ValueNode node = ValueNode.toStringNode("void", "(invoked; no return value)");
            return CaptureResult.ok(code, node);
        }
        // render the return value using the capture renderer
        try {
            ValueNode node = renderReturnValue(result, limits, thread);
            return CaptureResult.ok(code, node);
        } catch (Exception e) {
            return CaptureResult.error(code, "return render failed: " + e.getMessage());
        }
    }

    private static ValueNode renderReturnValue(Value v, ExprCapture.Limits limits, ThreadReference thread) {
        // Delegate to the same renderer capture uses; it is private, so replicate the
        // trivial cases here. For object/array returns we fall back to a truncated toString.
        if (v == null) return ValueNode.nullVal("Object");
        if (v instanceof PrimitiveValue pv) return ValueNode.primitive(v.type().name(), primitiveLiteral(pv));
        if (v instanceof StringReference sr) return ValueNode.stringVal("java.lang.String", truncate(sr.value(), limits.toStringLimit));
        if (v instanceof ArrayReference arr) {
            int total = arr.length();
            int take = Math.min(total, limits.collectionLimit);
            java.util.List<ValueNode> elems = new java.util.ArrayList<>(take);
            for (int i = 0; i < take; i++) elems.add(renderReturnValue(arr.getValue(i), limits, thread));
            return ValueNode.array(v.type().name(), elems, total > take, total);
        }
        if (v instanceof ObjectReference obj) {
            return ValueNode.toStringNode(obj.referenceType().name(), truncate(safeToString(obj, thread), limits.toStringLimit));
        }
        return ValueNode.nullVal("Object");
    }

    private static Object primitiveLiteral(PrimitiveValue pv) {
        if (pv instanceof BooleanValue b) return b.value();
        if (pv instanceof IntegerValue i) return i.value();
        if (pv instanceof LongValue l) return l.value();
        if (pv instanceof DoubleValue d) return d.value();
        if (pv instanceof FloatValue f) return f.value();
        if (pv instanceof ShortValue s) return s.value();
        if (pv instanceof ByteValue by) return by.value();
        if (pv instanceof CharValue c) return String.valueOf(c.value());
        return pv.toString();
    }

    private static String safeToString(ObjectReference obj, ThreadReference thread) {
        if (thread != null) {
            try {
                for (Method m : obj.referenceType().methodsByName("toString")) {
                    if (!m.isStatic() && m.argumentTypeNames().isEmpty()) {
                        Value r = obj.invokeMethod(thread, m, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
                        if (r instanceof StringReference sr) return sr.value();
                        if (r != null) return r.toString();
                    }
                }
            } catch (Throwable ignored) {}
        }
        return "<" + obj.referenceType().name() + "@" + Long.toHexString(obj.uniqueID()) + ">";
    }

    private static String truncate(String s, int limit) {
        if (s == null) return null;
        if (s.length() <= limit) return s;
        return s.substring(0, limit) + "...(" + s.length() + " chars)";
    }
}

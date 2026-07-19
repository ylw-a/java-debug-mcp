package com.ylw.jdbmcp.debug;

import com.sun.jdi.*;

import java.util.*;

import com.ylw.jdbmcp.snapshot.CaptureResult;
import com.ylw.jdbmcp.snapshot.ValueNode;

/**
 * The heart of the tool. Evaluates a pre-declared capture expression against a
 * suspended {@link StackFrame} and renders the result as a {@link ValueNode} tree.
 *
 * <p>The AI declares exactly what it wants to see <em>before</em> the breakpoint fires;
 * at hit time this class walks each declared path and records a lightweight snapshot.
 * No full heap dump is ever handed to the AI.
 *
 * <p>Expression grammar:
 * <pre>
 *   this                       current object
 *   this.id                    field of current object
 *   user.id                    local variable field
 *   user.address.id            nested path
 *   user.getAddress().getId()  via getter
 *   args[0]                    method parameter by position
 *   args[0].name               parameter field
 *   tags[i]                    array index on any value
 * </pre>
 *
 * <p>Null-break: if an intermediate segment resolves to null, evaluation stops and the
 * breaking segment is named (e.g. {@code user.address.id} with {@code address==null}
 * yields {@code null_break at "address"}). Knowing <em>where</em> it broke beats a bare null.
 *
 * <p>Safety: capture is read-only: field reads and no-arg getters (get-prefixed, is-prefixed,
 * and a small safe-list) only. Arbitrary method calls belong to {@link ExprEval} (consent-gated),
 * which reuses {@link #resolveValue} to obtain the receiver.
 */
public final class ExprCapture {

    /** Tunable limits extracted from config. */
    public static final class Limits {
        public final int pathDepth;       // max dereferences after the root
        public final int renderDepth;     // max object-field expansion depth
        public final int toStringLimit;
        public final int collectionLimit;
        public final int maxFields;
        public final boolean safeMode;    // no method invocation (getters, toString, List size/get)
        public final int maxRenderNodes;  // per-capture node budget (explosion guard)

        public Limits(int pathDepth, int renderDepth, int toStringLimit, int collectionLimit, int maxFields) {
            this(pathDepth, renderDepth, toStringLimit, collectionLimit, maxFields, false, 1000);
        }

        public Limits(int pathDepth, int renderDepth, int toStringLimit, int collectionLimit,
                      int maxFields, boolean safeMode) {
            this(pathDepth, renderDepth, toStringLimit, collectionLimit, maxFields, safeMode, 1000);
        }

        public Limits(int pathDepth, int renderDepth, int toStringLimit, int collectionLimit,
                      int maxFields, boolean safeMode, int maxRenderNodes) {
            this.pathDepth = pathDepth;
            this.renderDepth = renderDepth;
            this.toStringLimit = toStringLimit;
            this.collectionLimit = collectionLimit;
            this.maxFields = maxFields;
            this.safeMode = safeMode;
            this.maxRenderNodes = maxRenderNodes <= 0 ? 1000 : maxRenderNodes;
        }

        /** Copy with a different renderDepth (used for shallow bare-root rendering). */
        public Limits withRenderDepth(int rd) {
            return new Limits(pathDepth, rd, toStringLimit, collectionLimit, maxFields, safeMode, maxRenderNodes);
        }
    }

    /** Mutable per-capture render budget + shallow flag (threaded through renderValue). */
    static final class RenderCtx {
        int used = 0;
        final int max;
        final boolean shallow;
        RenderCtx(int max, boolean shallow) { this.max = max; this.shallow = shallow; }
        boolean exhausted() { return used >= max; }
        void tick() { used++; }
    }

    /** Outcome of resolving a path to a raw {@link Value} (no rendering). */
    public static final class Resolution {
        public final Value value;
        public final String status;     // ok | null_break | not_found | error | depth_limit
        public final String breakAt;
        public final String message;

        private Resolution(Value v, String status, String breakAt, String message) {
            this.value = v; this.status = status; this.breakAt = breakAt; this.message = message;
        }
        static Resolution ok(Value v) { return new Resolution(v, "ok", null, null); }
        static Resolution nullBreak(String at) { return new Resolution(null, "null_break", at, null); }
        static Resolution notFound(String m) { return new Resolution(null, "not_found", null, m); }
        static Resolution error(String m) { return new Resolution(null, "error", null, m); }
        static Resolution depthLimit(String m) { return new Resolution(null, "depth_limit", null, m); }
    }

    // ---- segments ----
    sealed interface Segment permits RootThis, RootArg, RootVar, FieldSeg, GetterSeg, IndexSeg, VarIndexSeg {}
    record RootThis() implements Segment {}
    record RootArg(int index) implements Segment {}
    record RootVar(String name) implements Segment {}
    record FieldSeg(String name) implements Segment {}
    record GetterSeg(String name) implements Segment {}
    record IndexSeg(int index) implements Segment {}
    /** Variable index: {@code [varName]}, resolved at capture time from the frame's locals. */
    record VarIndexSeg(String name) implements Segment {}

    private ExprCapture() {}

    // ---------------------------------------------------------------- parse
    public static List<String> validate(String expr) {
        try {
            parse(expr);
            return Collections.emptyList();
        } catch (Exception e) {
            return List.of(e.getMessage());
        }
    }

    static List<Segment> parse(String expr) {
        String s = expr.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("empty expression");
        int len = s.length();
        int pos = 0;
        List<Segment> segs = new ArrayList<>();

        if (s.startsWith("this") && (len == 4 || !isIdentChar(s.charAt(4)))) {
            segs.add(new RootThis());
            pos = 4;
        } else if (s.startsWith("args") && (len == 4 || s.charAt(4) == '[')) {
            int[] r = parseIndex(s, 4);
            segs.add(new RootArg(r[0]));
            pos = r[1];
        } else {
            int end = readIdent(s, 0);
            segs.add(new RootVar(s.substring(0, end)));
            pos = end;
        }

        while (pos < len) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t') { pos++; continue; }
            if (c == '.') {
                pos++;
                int end = readIdent(s, pos);
                String name = s.substring(pos, end);
                if (name.isEmpty()) throw new IllegalArgumentException("expected field/method name after '.'");
                pos = end;
                if (pos < len && s.charAt(pos) == '(') {
                    pos++;
                    while (pos < len && s.charAt(pos) == ' ') pos++;
                    if (pos < len && s.charAt(pos) == ')') { pos++; }
                    else throw new IllegalArgumentException("capture getters must be no-arg: " + name + "()");
                    segs.add(new GetterSeg(name));
                } else {
                    segs.add(new FieldSeg(name));
                }
            } else if (c == '[') {
                pos++;
                while (pos < len && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t')) pos++;
                if (pos < len && Character.isDigit(s.charAt(pos))) {
                    // numeric literal: [0], [42]
                    int[] r = parseIndex(s, pos - 1);
                    segs.add(new IndexSeg(r[0]));
                    pos = r[1];
                } else if (pos < len && isIdentStart(s.charAt(pos))) {
                    // variable name: [mid], [i]
                    int end = readIdent(s, pos);
                    String varName = s.substring(pos, end);
                    pos = end;
                    while (pos < len && (s.charAt(pos) == ' ' || s.charAt(pos) == '\t')) pos++;
                    if (pos >= len || s.charAt(pos) != ']')
                        throw new IllegalArgumentException("expected ']' after variable name in []");
                    pos++;
                    segs.add(new VarIndexSeg(varName));
                } else {
                    throw new IllegalArgumentException("expected number or variable name in [] at position " + pos);
                }
            } else {
                throw new IllegalArgumentException("unexpected '" + c + "' at position " + pos);
            }
        }
        return segs;
    }

    private static int readIdent(String s, int start) {
        int i = start;
        while (i < s.length() && isIdentChar(s.charAt(i))) i++;
        return i;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int[] parseIndex(String s, int start) {
        if (start >= s.length() || s.charAt(start) != '[') {
            throw new IllegalArgumentException("expected '[' at position " + start);
        }
        int i = start + 1;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        int numStart = i;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == numStart) throw new IllegalArgumentException("expected number in [] at position " + start);
        int idx;
        try { idx = Integer.parseInt(s.substring(numStart, i)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("bad index in [] at position " + start); }
        while (i < s.length() && s.charAt(i) == ' ') i++;
        if (i >= s.length() || s.charAt(i) != ']') throw new IllegalArgumentException("expected ']' at position " + i);
        i++;
        return new int[]{idx, i};
    }

    // ---------------------------------------------------------------- resolve (reusable)

    /** Resolve with optional exception root ({@code e} in exception bp captures). */
    public static Resolution resolveValue(ThreadReference thread, StackFrame frame, String expr,
                                          Limits limits, Value eRoot) {
        List<Segment> segs;
        try {
            segs = parse(expr);
        } catch (Exception e) {
            return Resolution.error("parse error: " + e.getMessage());
        }
        return resolveValue(thread, frame, segs, limits, eRoot);
    }

    public static Resolution resolveValue(ThreadReference thread, StackFrame frame, List<Segment> segs,
                                          Limits limits, Value eRoot) {
        int derefCount = segs.size() - 1;
        if (derefCount > limits.pathDepth) {
            return Resolution.depthLimit("path has " + derefCount + " dereferences; max is " + limits.pathDepth);
        }

        Value cur;
        try {
            cur = resolveRoot(frame, segs.get(0), eRoot);
        } catch (NotFound nf) {
            return Resolution.notFound(nf.getMessage());
        } catch (Exception e) {
            return Resolution.error("root resolution failed: " + e.getMessage());
        }
        if (cur == null) {
            if (segs.size() == 1) return Resolution.ok(null);
            return Resolution.nullBreak(labelOf(segs.get(0)));
        }

        for (int i = 1; i < segs.size(); i++) {
            Segment seg = segs.get(i);
            boolean isLast = (i == segs.size() - 1);
            try {
                cur = dereference(thread, frame, cur, seg, limits);
            } catch (NotFound nf) {
                return Resolution.notFound(nf.getMessage());
            } catch (Exception e) {
                return Resolution.error("at " + labelOf(seg) + ": " + e.getMessage());
            }
            if (cur == null) {
                if (isLast) return Resolution.ok(null);
                return Resolution.nullBreak(labelOf(seg));
            }
        }
        return Resolution.ok(cur);
    }

    public static Resolution resolveValue(ThreadReference thread, StackFrame frame, String expr, Limits limits) {
        return resolveValue(thread, frame, expr, limits, null);
    }

    private static Value resolveRoot(StackFrame frame, Segment root, Value eRoot) throws Exception {
        if (root instanceof RootThis) {
            return frame.thisObject();
        }
        if (root instanceof RootArg ra) {
            List<Value> args = frame.getArgumentValues();
            if (ra.index() < 0 || ra.index() >= args.size()) {
                throw new NotFound("args[" + ra.index() + "] out of range (arity=" + args.size() + ")");
            }
            return args.get(ra.index());
        }
        if (root instanceof RootVar rv) {
            // In exception-bp captures, 'e' resolves to the thrown exception
            if ("e".equals(rv.name()) && eRoot != null) {
                return eRoot;
            }
            LocalVariable lv = frame.visibleVariableByName(rv.name());
            if (lv == null) throw new NotFound("no local variable named '" + rv.name() + "'");
            return frame.getValue(lv);
        }
        throw new IllegalArgumentException("unsupported root segment");
    }

    // ---------------------------------------------------------------- evaluate (resolve + render)

    /** Full evaluate supporting an optional exception root ({@code e} in exception bps). */
    public static CaptureResult evaluate(
            ThreadReference thread, StackFrame frame, String expr, Limits limits, Value eRoot) {
        List<Segment> segs;
        try {
            segs = parse(expr);
        } catch (Exception e) {
            return CaptureResult.error(expr, "parse error: " + e.getMessage());
        }
        Resolution r = resolveValue(thread, frame, segs, limits, eRoot);
        // Bare root (no path after this/args[N]/var): render shallow so `this` doesn't explode.
        // Only plain objects get shallow treatment; lists/arrays/boxed/strings render normally.
        boolean bareRoot = segs.size() == 1;
        return buildResult(thread, expr, r, limits, bareRoot);
    }

    public static CaptureResult evaluate(
            ThreadReference thread, StackFrame frame, String expr, Limits limits) {
        return evaluate(thread, frame, expr, limits, null);
    }

    private static CaptureResult buildResult(ThreadReference thread, String expr, Resolution r,
                                             Limits limits, boolean bareRoot) {
        return switch (r.status) {
            case "ok" -> {
                if (r.value == null) {
                    yield CaptureResult.ok(expr, ValueNode.nullVal("Object"));
                }
                try {
                    boolean shallow = bareRoot && isPlainObject(r.value);
                    RenderCtx ctx = new RenderCtx(limits.maxRenderNodes, shallow);
                    yield CaptureResult.ok(expr, renderValue(r.value, limits, 0, new HashSet<>(), thread, ctx));
                } catch (Exception e) {
                    yield CaptureResult.error(expr, "render failed: " + e.getMessage());
                }
            }
            case "null_break" -> CaptureResult.nullBreak(expr, r.breakAt);
            case "not_found" -> CaptureResult.notFound(expr, r.message);
            case "depth_limit" -> CaptureResult.depthLimit(expr, r.message);
            default -> CaptureResult.error(expr, r.message);
        };
    }

    /** A plain object (not array, not String, not boxed, not List) - eligible for shallow rendering. */
    private static boolean isPlainObject(Value v) {
        if (!(v instanceof ObjectReference obj)) return false;
        if (v instanceof ArrayReference) return false;
        if (v instanceof StringReference) return false;
        if (BOXED_TYPES.contains(obj.referenceType().name())) return false;
        return !isList(obj.referenceType());
    }

    private static Value dereference(ThreadReference thread, StackFrame frame, Value cur, Segment seg,
                                     Limits limits) throws Exception {
        if (seg instanceof FieldSeg fs) {
            // Special-case: array.length (JVM arraylength instruction, not a field in the type system).
            if (cur instanceof ArrayReference arr && "length".equals(fs.name())) {
                return arr.virtualMachine().mirrorOf(arr.length());
            }
            if (!(cur instanceof ObjectReference obj)) {
                throw new NotFound("cannot read field '" + fs.name() + "' on non-object (" + typeLabel(cur) + ")");
            }
            Field f = obj.referenceType().fieldByName(fs.name());
            if (f == null) {
                for (Field af : obj.referenceType().allFields()) {
                    if (af.name().equals(fs.name()) && !af.isStatic()) { f = af; break; }
                }
            }
            if (f == null) throw new NotFound("no field '" + fs.name() + "' on " + obj.referenceType().name());
            return obj.getValue(f);
        }
        if (seg instanceof GetterSeg gs) {
            if (limits.safeMode) {
                throw new NotFound("safe_mode: getter " + gs.name() + "() disabled; read the field directly instead");
            }
            if (!(cur instanceof ObjectReference obj)) {
                throw new NotFound("cannot call " + gs.name() + "() on non-object (" + typeLabel(cur) + ")");
            }
            Method m = findGetter(obj.referenceType(), gs.name());
            if (m == null) {
                throw new NotFound("no read-only getter '" + gs.name() + "()' on " + obj.referenceType().name()
                        + " (capture allows get-/is-prefixed getters and a small safe-list; use eval for other methods)");
            }
            return invoke(thread, obj, m);
        }
        if (seg instanceof IndexSeg is) {
            return indexInto(thread, cur, is.index());
        }
        if (seg instanceof VarIndexSeg vs) {
            LocalVariable lv = frame.visibleVariableByName(vs.name());
            if (lv == null) throw new NotFound("no variable named '" + vs.name() + "' for index");
            Value idxVal = frame.getValue(lv);
            int idx;
            if (idxVal instanceof IntegerValue iv) {
                idx = iv.value();
            } else if (idxVal instanceof PrimitiveValue pv) {
                try { idx = Integer.parseInt(pv.toString()); } catch (NumberFormatException e) {
                    throw new NotFound("variable '" + vs.name() + "' is not an int for index (type=" + idxVal.type().name() + ")");
                }
            } else {
                throw new NotFound("variable '" + vs.name() + "' is not an int for index (type=" + typeLabel(idxVal) + ")");
            }
            return indexInto(thread, cur, idx);
        }
        throw new IllegalArgumentException("unsupported segment");
    }

    /** Shared indexing logic for arrays and java.util.List (used by both [N] and [var]). */
    private static Value indexInto(ThreadReference thread, Value cur, int idx) throws NotFound {
        if (cur instanceof ArrayReference arr) {
            int len = arr.length();
            if (idx < 0 || idx >= len) {
                throw new NotFound("index [" + idx + "] out of range (length=" + len + ")");
            }
            return arr.getValue(idx);
        }
        // java.util.List: positional access via get(int) (read-only).
        if (cur instanceof ObjectReference obj && isList(obj.referenceType())) {
            try {
                return invokeListGet(thread, obj, idx);
            } catch (Exception e) {
                throw new NotFound("list index [" + idx + "] failed: " + e.getMessage());
            }
        }
        throw new NotFound("cannot index [" + idx + "] into non-array/non-List ("
                + typeLabel(cur) + "); [i] supports arrays, java.util.List, and args by position");
    }

    private static Method findGetter(ReferenceType rt, String name) {
        List<Method> methods = rt.methodsByName(name);
        for (Method m : methods) {
            if (m.isStatic()) continue;
            if (!m.argumentTypeNames().isEmpty()) continue;
            if ("void".equals(m.returnTypeName())) continue;
            if (isSafeGetterName(name)) return m;
        }
        return null;
    }

    private static final Set<String> BOXED_TYPES = Set.of(
            "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
            "java.lang.Short", "java.lang.Byte", "java.lang.Boolean", "java.lang.Character");

    /** Value types whose internal fields (mag, intVal, scale, ...) are noise: render as their
     *  toString() value instead of expanding. Skipped in safe_mode (falls through to field reads). */
    private static final Set<String> VALUE_TYPES = Set.of(
            "java.math.BigDecimal", "java.math.BigInteger", "java.util.UUID", "java.util.Date",
            "java.time.LocalDate", "java.time.LocalTime", "java.time.LocalDateTime",
            "java.time.ZonedDateTime", "java.time.OffsetDateTime", "java.time.Instant",
            "java.time.Duration", "java.time.Period", "java.time.Year", "java.time.YearMonth",
            "java.time.MonthDay", "java.util.Currency", "java.util.Locale");

    private static final Set<String> SAFE_NOARG_METHODS = Set.of(
            "size", "isEmpty", "length", "toString", "hashCode", "getClass");

    private static boolean isSafeGetterName(String name) {
        if (name.startsWith("get") && name.length() > 3) return true;
        if (name.startsWith("is") && name.length() > 2) return true;
        return SAFE_NOARG_METHODS.contains(name);
    }

    private static Value invoke(ThreadReference thread, ObjectReference obj, Method m) throws Exception {
        try {
            return obj.invokeMethod(thread, m, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (InvocationException ie) {
            throw new RuntimeException("getter " + m.name() + "() threw: " + ie.exception());
        } catch (IncompatibleThreadStateException | InvalidTypeException | ClassNotLoadedException e) {
            throw new RuntimeException("getter " + m.name() + "() invocation failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- render

    /** Public entry point for rendering a single value (used by get_variables, etc.). */
    public static ValueNode renderValue(Value v, Limits limits, ThreadReference thread) {
        return renderValue(v, limits, 0, new HashSet<>(), thread, new RenderCtx(limits.maxRenderNodes, false));
    }

    private static ValueNode renderValue(Value v, Limits limits, int depth, Set<Long> visited,
                                         ThreadReference thread, RenderCtx ctx) {
        if (v == null) {
            return ValueNode.nullVal("null");
        }
        if (ctx.exhausted()) {
            return ValueNode.toStringNode(typeLabel(v),
                    "<render budget exceeded (" + ctx.max + " nodes); narrow the capture path "
                            + "(e.g. this.field) or call explore on a specific sub-path>");
        }
        ctx.tick();
        // Effective expansion cap: shallow (bare `this`) -> depth 1 (direct fields only, no recursion).
        int cap = ctx.shallow ? 1 : limits.renderDepth;
        if (v instanceof PrimitiveValue pv) {
            return ValueNode.primitive(typeLabel(v), primitiveLiteral(pv));
        }
        if (v instanceof StringReference sr) {
            return ValueNode.stringVal("java.lang.String", truncate(sr.value(), limits.toStringLimit));
        }
        if (v instanceof ArrayReference arr) {
            if (depth >= cap) {
                return ValueNode.toStringNode(typeLabel(v), truncate(safeToString(arr, thread, limits), limits.toStringLimit));
            }
            int total = arr.length();
            int take = Math.min(total, limits.collectionLimit);
            List<ValueNode> elems = new ArrayList<>(take);
            for (int i = 0; i < take; i++) {
                if (ctx.exhausted()) break;
                elems.add(renderValue(arr.getValue(i), limits, depth + 1, visited, thread, ctx));
            }
            return ValueNode.array(typeLabel(v), elems, total > take || ctx.exhausted(), total);
        }
        if (v instanceof ObjectReference obj) {
            String type = obj.referenceType().name();
            // Boxed primitives: read the 'value' field directly (no invoke, safe_mode safe).
            if (BOXED_TYPES.contains(type)) {
                Field vf = obj.referenceType().fieldByName("value");
                if (vf != null) {
                    try {
                        Value inner = obj.getValue(vf);
                        if (inner instanceof PrimitiveValue pv) {
                            return ValueNode.primitive(type, primitiveLiteral(pv));
                        }
                    } catch (Throwable ignored) { /* fall through to generic rendering */ }
                }
            }
            // Value types (BigDecimal, Date, etc.): render as toString (internals are noise).
            // safe_mode falls through to field reads (no invocation).
            if (!limits.safeMode && VALUE_TYPES.contains(type)) {
                return ValueNode.stringVal(type, truncate(safeToString(obj, thread, limits), limits.toStringLimit));
            }
            // Render java.util.List as an expanded array (size() + get(i)), per the
            // "collections expand to first N elements" rule. Positional [i] indexing on
            // Lists is intentionally NOT supported in capture (use the whole-list render).
            if (isList(obj.referenceType())) {
                return renderList(thread, obj, type, limits, depth, visited, ctx, cap);
            }
            if (depth >= cap) {
                return ValueNode.toStringNode(type, truncate(safeToString(obj, thread, limits), limits.toStringLimit));
            }
            long id = obj.uniqueID();
            if (visited.contains(id)) {
                return ValueNode.toStringNode(type, "<cycle> " + truncate(safeToString(obj, thread, limits), limits.toStringLimit));
            }
            visited.add(id);
            List<Field> allFields;
            try {
                allFields = obj.referenceType().allFields();
            } catch (Throwable t) {
                return ValueNode.toStringNode(type, truncate(safeToString(obj, thread, limits), limits.toStringLimit));
            }
            Map<String, ValueNode> fields = new LinkedHashMap<>();
            int shown = 0;
            boolean truncatedFields = false;
            for (Field f : allFields) {
                if (shown >= limits.maxFields) { truncatedFields = true; break; }
                if (ctx.exhausted()) { truncatedFields = true; break; }
                Value fv;
                try { fv = obj.getValue(f); }
                catch (Throwable t) { fv = null; }
                fields.put(f.name(), renderValue(fv, limits, depth + 1, visited, thread, ctx));
                shown++;
            }
            ValueNode node = ValueNode.object(type, fields);
            if (truncatedFields) {
                node.truncated = true;
                node.totalElements = allFields.size();
            }
            return node;
        }
        return ValueNode.nullVal(typeLabel(v));
    }

    private static boolean isList(ReferenceType rt) {
        if (!(rt instanceof ClassType ct)) return false;
        try {
            for (InterfaceType it : ct.allInterfaces()) {
                if ("java.util.List".equals(it.name())) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Render a java.util.List by invoking size() and get(i) for the first N elements. */
    private static ValueNode renderList(ThreadReference thread, ObjectReference obj,
                                        String type, Limits limits, int depth, Set<Long> visited,
                                        RenderCtx ctx, int cap) {
        long id = obj.uniqueID();
        if (visited.contains(id)) {
            return ValueNode.toStringNode(type, "<cycle> " + truncate(safeToString(obj, thread, limits), limits.toStringLimit));
        }
        visited.add(id);
        if (depth >= cap) {
            return ValueNode.toStringNode(type, truncate(safeToString(obj, thread, limits), limits.toStringLimit));
        }
        if (limits.safeMode) {
            return ValueNode.toStringNode(type, "<List, safe_mode: no size/get invocation>");
        }
        int total = invokeIntNoArg(thread, obj, "size", -1);
        if (total < 0) {
            return ValueNode.toStringNode(type, truncate(safeToString(obj, thread, limits), limits.toStringLimit));
        }
        int take = Math.min(total, limits.collectionLimit);
        // find get(int) method
        Method getMethod = null;
        for (Method m : obj.referenceType().methodsByName("get")) {
            if (!m.isStatic() && m.argumentTypeNames().size() == 1
                    && "int".equals(m.argumentTypeNames().get(0))) {
                getMethod = m;
                break;
            }
        }
        List<ValueNode> elems = new ArrayList<>(take);
        if (getMethod != null) {
            for (int i = 0; i < take; i++) {
                if (ctx.exhausted()) break;
                try {
                    Value arg = obj.virtualMachine().mirrorOf(i);
                    Value e = obj.invokeMethod(thread, getMethod, List.of(arg), ObjectReference.INVOKE_SINGLE_THREADED);
                    elems.add(renderValue(e, limits, depth + 1, visited, thread, ctx));
                } catch (Throwable t) {
                    elems.add(ValueNode.toStringNode("?", "<get(" + i + ") failed: " + t.getMessage() + ">"));
                }
            }
        }
        return ValueNode.array(type, elems, total > take || ctx.exhausted(), total);
    }

    private static int invokeIntNoArg(ThreadReference thread, ObjectReference obj, String methodName, int fallback) {
        try {
            for (Method m : obj.referenceType().methodsByName(methodName)) {
                if (!m.isStatic() && m.argumentTypeNames().isEmpty() && "int".equals(m.returnTypeName())) {
                    Value r = obj.invokeMethod(thread, m, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
                    if (r instanceof IntegerValue iv) return iv.value();
                }
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    /** Positional access into a java.util.List via get(int), with a size() range check. */
    private static Value invokeListGet(ThreadReference thread, ObjectReference obj, int index) throws Exception {
        int size = invokeIntNoArg(thread, obj, "size", -1);
        if (size < 0) throw new NotFound("could not determine List size for indexing");
        if (index < 0 || index >= size) {
            throw new NotFound("index [" + index + "] out of range (size=" + size + ")");
        }
        Method getMethod = null;
        for (Method m : obj.referenceType().methodsByName("get")) {
            if (!m.isStatic() && m.argumentTypeNames().size() == 1
                    && "int".equals(m.argumentTypeNames().get(0))) {
                getMethod = m;
                break;
            }
        }
        if (getMethod == null) throw new NotFound("no get(int) method on " + obj.referenceType().name());
        Value arg = obj.virtualMachine().mirrorOf(index);
        try {
            return obj.invokeMethod(thread, getMethod, List.of(arg), ObjectReference.INVOKE_SINGLE_THREADED);
        } catch (InvocationException ie) {
            throw new NotFound("get(" + index + ") threw: " + ie.exception().referenceType().name());
        } catch (IncompatibleThreadStateException | InvalidTypeException | ClassNotLoadedException e) {
            throw new RuntimeException("get(" + index + ") invocation failed: " + e.getMessage());
        }
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

    private static String safeToString(ObjectReference obj, ThreadReference thread, Limits limits) {
        if (limits != null && limits.safeMode) {
            return "<" + obj.referenceType().name() + " (safe_mode: no toString)>";
        }
        if (thread != null) {
            try {
                for (Method m : obj.referenceType().methodsByName("toString")) {
                    if (!m.isStatic() && m.argumentTypeNames().isEmpty()) {
                        Value r = obj.invokeMethod(thread, m, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
                        if (r instanceof StringReference sr) return sr.value();
                        if (r != null) return r.toString();
                    }
                }
            } catch (Throwable ignored) {
                // fall through to placeholder
            }
        }
        return "<" + obj.referenceType().name() + "@" + Long.toHexString(obj.uniqueID()) + ">";
    }

    private static String truncate(String s, int limit) {
        if (s == null) return null;
        if (s.length() <= limit) return s;
        return s.substring(0, limit) + "...(" + s.length() + " chars)";
    }

    private static String typeLabel(Value v) {
        try {
            if (v == null) return "null";
            return v.type().name();
        } catch (Throwable t) {
            return "?";
        }
    }

    static String labelOf(Segment seg) {
        if (seg instanceof RootThis) return "this";
        if (seg instanceof RootArg ra) return "args[" + ra.index() + "]";
        if (seg instanceof RootVar rv) return rv.name();
        if (seg instanceof FieldSeg fs) return fs.name();
        if (seg instanceof GetterSeg gs) return gs.name() + "()";
        if (seg instanceof IndexSeg is) return "[" + is.index() + "]";
        if (seg instanceof VarIndexSeg vs) return "[" + vs.name() + "]";
        return "?";
    }

    static final class NotFound extends Exception {
        NotFound(String msg) { super(msg); }
    }
}

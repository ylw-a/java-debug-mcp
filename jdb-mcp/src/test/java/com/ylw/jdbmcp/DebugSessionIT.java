package com.ylw.jdbmcp;

import com.ylw.jdbmcp.debug.DebugSession;
import com.ylw.jdbmcp.snapshot.CaptureResult;
import com.ylw.jdbmcp.snapshot.Hit;
import com.ylw.jdbmcp.snapshot.ValueNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end debug test: launches the demo JVM, sets breakpoints with pre-declared captures,
 * and asserts the captured snapshots (null-break, collection truncation, getter paths, etc.).
 *
 * <p>Requires the demo jar built at {@code ../demo/target/demo.jar} (run {@code mvn -pl demo package} first).
 */
class DebugSessionIT {

    private DebugSession session;
    private StartParams startParams;

    @BeforeEach
    void setUp() throws Exception {
        Path demoJar = Path.of("F:/project/java-debug/demo/target/demo.jar");
        if (!Files.exists(demoJar)) {
            demoJar = Path.of("../demo/target/demo.jar");
        }
        assumeExists(demoJar);

        startParams = new StartParams();
        startParams.classpath = List.of(demoJar.toAbsolutePath().toString());
        startParams.mainClass = "com.ylw.demo.DemoApp";
        startParams.suspend = true;
        startParams.workingDir = Path.of("F:/project/java-debug").toAbsolutePath().toString();

        session = new DebugSession();
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            try { session.stop(); } catch (Throwable ignored) {}
        }
    }

    @Test
    void modeA_capturesNullBreakAndCollectionTruncation() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);
        System.out.println("[test] started: " + start);

        List<String> captures = List.of(
                "args[0].name",
                "args[0].address.id",
                "args[0].getAddress().getId()",
                "args[1].items[0].sku",
                "args[1].items",
                "this.serviceName",
                "this.callCount",
                "user.name"       // #7: named parameter capture (should work with -g)
        );

        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null, captures, "A", false, null, null, null, null, null);
        assertNull(bp.get("error"), "set_breakpoint failed: " + bp);
        System.out.println("[test] breakpoint armed: " + bp);

        // Demo calls processUser 3 times; poll until at least 3 hits land.
        List<Hit> hits = pollHits(session, (String) bp.get("breakpointId"), 3, 15);
        System.out.println("[test] got " + hits.size() + " hits");

        // Inspect the first hit's captures.
        Hit h0 = hits.get(0);
        for (CaptureResult cr : h0.captures) {
            System.out.println("[capture] " + cr.expr + " -> " + cr.status
                    + (cr.nullBreakAt != null ? " at " + cr.nullBreakAt : "")
                    + (cr.error != null ? " :: " + cr.error : "")
                    + (cr.value != null ? " :: " + cr.value.kind + "=" + cr.value.value : ""));
        }
        Map<String, CaptureResult> byExpr = new java.util.HashMap<>();
        for (CaptureResult cr : h0.captures) byExpr.put(cr.expr, cr);

        // args[0].name -> string "user-1"
        CaptureResult name = byExpr.get("args[0].name");
        assertNotNull(name);
        assertEquals("ok", name.status, "args[0].name status");
        assertEquals("string", name.value.kind);
        assertTrue(name.value.value.toString().startsWith("user-"), "name=" + name.value.value);

        // args[0].address.id -> null_break at "address"
        CaptureResult addrId = byExpr.get("args[0].address.id");
        assertNotNull(addrId);
        assertEquals("null_break", addrId.status, "address.id should null-break");
        assertEquals("address", addrId.nullBreakAt, "should break at 'address'");

        // args[0].getAddress().getId() -> null_break at "getAddress()"
        CaptureResult getter = byExpr.get("args[0].getAddress().getId()");
        assertNotNull(getter);
        assertEquals("null_break", getter.status, "getAddress().getId() should null-break");
        assertEquals("getAddress()", getter.nullBreakAt);

        // args[1].items[0].sku -> "SKU-0" (List positional access via get(int))
        CaptureResult sku = byExpr.get("args[1].items[0].sku");
        assertNotNull(sku);
        assertEquals("ok", sku.status);
        assertEquals("string", sku.value.kind);
        assertTrue(sku.value.value.toString().startsWith("SKU-"), "sku=" + sku.value.value);

        // args[1].items -> array, truncated, totalElements 25; element 0 is an Item with sku.
        CaptureResult items = byExpr.get("args[1].items");
        assertNotNull(items);
        assertEquals("ok", items.status);
        assertEquals("array", items.value.kind);
        assertEquals(Boolean.TRUE, items.value.truncated, "items should be truncated");
        assertEquals(25, items.value.totalElements, "items totalElements");
        assertEquals(20, items.value.elements.size(), "items expanded to collectionLimit");
        ValueNode elem0 = items.value.elements.get(0);
        assertEquals("object", elem0.kind, "first item should render as object");
        assertNotNull(elem0.fields.get("sku"), "first item should have a sku field");
        assertTrue(elem0.fields.get("sku").value.toString().startsWith("SKU-"),
                "first item sku=" + elem0.fields.get("sku").value);

        // this.serviceName -> "userService"
        CaptureResult svc = byExpr.get("this.serviceName");
        assertNotNull(svc);
        assertEquals("ok", svc.status);
        assertEquals("userService", svc.value.value);

        // #7: named parameter 'user' should be accessible at method entry (demo has -g:vars)
        CaptureResult userName = byExpr.get("user.name");
        assertNotNull(userName, "named param 'user' should be visible with -g");
        assertEquals("ok", userName.status, "user.name should resolve: " + userName.status
                + (userName.error != null ? " " + userName.error : ""));
        assertTrue(userName.value.value.toString().startsWith("user-"), "user.name=" + userName.value.value);

        // hit counting: 3 hits with ordinals 1,2,3
        assertEquals(3, hits.size());
        assertEquals(1, hits.get(0).hitOrdinal);
        assertEquals(2, hits.get(1).hitOrdinal);
        assertEquals(3, hits.get(2).hitOrdinal);

        System.out.println("[test] mode A assertions passed");
    }

    @Test
    void modeB_armsNonBlockingResumeBlocksAndExplore() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);

        List<String> captures = List.of("args[0].name", "this.serviceName");
        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null, captures, "B", false, 20, null, null, null, null);
        assertNull(bp.get("error"), "set_breakpoint mode B failed: " + bp);
        assertTrue("armed".equals(bp.get("status")) || "armed_deferred".equals(bp.get("status")),
                "mode B set_breakpoint must be NON-BLOCKING (status=armed/armed_deferred): " + bp);

        // resume blocks until the hit
        Map<String, Object> r = session.resume();
        assertNull(r.get("error"), "resume (wait) failed: " + r);
        assertEquals("hit", r.get("status"), "resume should return the hit: " + r);
        assertEquals(Boolean.TRUE, r.get("jvmSuspended"));

        Hit hit = (Hit) r.get("hit");
        assertNotNull(hit);
        assertEquals("B", hit.mode);

        // explore while suspended
        Map<String, Object> exp = session.explore("args[0].address.id");
        assertNull(exp.get("error"), "explore failed: " + exp);
        CaptureResult expResult = (CaptureResult) exp.get("result");
        assertEquals("null_break", expResult.status);
        assertEquals("address", expResult.nullBreakAt);
        System.out.println("[test] explore result: " + exp);

        // resume (continue) - non-blocking now (one-shot)
        Map<String, Object> r2 = session.resume();
        assertNull(r2.get("error"), "resume (continue) failed: " + r2);
        assertEquals("resumed", r2.get("status"));
        System.out.println("[test] mode B non-blocking + resume + explore + resume passed");
    }

    @Test
    void resolveClass_resolvesUserService() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);
        // The demo references UserService; it should be loaded after the target runs a bit.
        // With suspend=y the target is paused at start, so classes may not be loaded yet.
        // Resume briefly to let it load, then check.
        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null, List.of("this.serviceName"), "A", false, null, null, null, null, null);
        assertNull(bp.get("error"), "bp failed: " + bp);
        pollHits(session, (String) bp.get("breakpointId"), 1, 10);

        Map<String, Object> cls = session.resolveClass("UserService", false);
        assertNull(cls.get("error"), "resolve_class failed: " + cls);
        List<?> cands = (List<?>) cls.get("candidates");
        assertNotNull(cands);
        assertTrue(cands.stream().anyMatch(c -> c.toString().contains("com.ylw.demo.UserService")),
                "expected com.ylw.demo.UserService in candidates: " + cands);
        System.out.println("[test] resolve_class OK: " + cands);
    }

    @Test
    void exceptionBreakpoint_capturesERoot() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);

        List<String> captures = List.of("e.getMessage()", "e.reason", "e.getClass().getName()");
        Map<String, Object> bp = session.setExceptionBreakpoint("DemoException", true, true, captures, "A", false, 10);
        assertNull(bp.get("error"), "set_exception_breakpoint failed: " + bp);
        System.out.println("[test] exception bp armed: " + bp);

        List<Hit> hits = pollHits(session, (String) bp.get("breakpointId"), 1, 20);
        assertFalse(hits.isEmpty(), "expected at least 1 exception hit");
        Hit h0 = hits.get(0);
        Map<String, CaptureResult> byExpr = new java.util.HashMap<>();
        for (CaptureResult cr : h0.captures) byExpr.put(cr.expr, cr);

        CaptureResult msg = byExpr.get("e.getMessage()");
        assertNotNull(msg);
        assertEquals("ok", msg.status);
        assertTrue(msg.value.value.toString().contains("demo error"), "msg=" + msg.value.value);

        CaptureResult reason = byExpr.get("e.reason");
        assertNotNull(reason);
        assertEquals("ok", reason.status);
        assertEquals("test exception for breakpoint", reason.value.value);

        System.out.println("[test] exception breakpoint test passed");
    }

    @Test
    void getFrames_returnsStackOnModeBHit() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);

        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null,
                List.of("this.serviceName"), "B", false, 20, null, null, null, null);
        assertNull(bp.get("error"), "mode B failed: " + bp);
        assertTrue("armed".equals(bp.get("status")) || "armed_deferred".equals(bp.get("status")),
                "mode B should arm non-blocking: " + bp);

        Map<String, Object> r = session.resume();
        assertNull(r.get("error"), "resume failed: " + r);
        assertEquals("hit", r.get("status"));

        // Top frame should be processUser
        Map<String, Object> frames = session.getFrames(null);
        assertNull(frames.get("error"), "get_frames failed: " + frames);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) frames.get("frames");
        assertNotNull(list);
        assertTrue(list.size() > 0, "should have at least 1 frame");
        assertEquals("processUser", list.get(0).get("method"));
        System.out.println("[test] get_frames: " + frames.get("count") + " frames, top=" + list.get(0).get("method"));

        session.resume();
    }

    @Test
    void getVariables_returnsLocalsOnModeBHit() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);

        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null,
                List.of("this.serviceName"), "B", false, 20, null, null, null, null);
        assertNull(bp.get("error"), "mode B failed: " + bp);
        assertTrue("armed".equals(bp.get("status")) || "armed_deferred".equals(bp.get("status")),
                "mode B should arm non-blocking: " + bp);

        Map<String, Object> r = session.resume();
        assertNull(r.get("error"), "resume failed: " + r);
        assertEquals("hit", r.get("status"));

        Map<String, Object> vars = session.getVariables(0, null);
        assertNull(vars.get("error"), "get_variables failed: " + vars);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) vars.get("variables");
        assertNotNull(list);
        // Should have at least 'user' and 'order' params
        boolean hasUser = list.stream().anyMatch(v -> "user".equals(v.get("name")));
        boolean hasOrder = list.stream().anyMatch(v -> "order".equals(v.get("name")));
        assertTrue(hasUser, "should have 'user' param");
        assertTrue(hasOrder, "should have 'order' param");
        System.out.println("[test] get_variables: " + list.size() + " vars, user=" + hasUser + " order=" + hasOrder);

        session.resume();
    }

    @Test
    void step_overChangesLine() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);

        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null,
                List.of("this.serviceName"), "B", false, 20, null, null, null, null);
        assertNull(bp.get("error"), "mode B failed: " + bp);
        assertTrue("armed".equals(bp.get("status")) || "armed_deferred".equals(bp.get("status")),
                "mode B should arm non-blocking: " + bp);

        Map<String, Object> r = session.resume();
        assertNull(r.get("error"), "resume failed: " + r);
        assertEquals("hit", r.get("status"));
        Hit hit = (Hit) r.get("hit");
        int originalLine = hit.line;

        Map<String, Object> step = session.step("over");
        assertNull(step.get("error"), "step failed: " + step);
        int newLine = (int) step.get("line");
        assertNotEquals(originalLine, newLine, "step should change line number");
        System.out.println("[test] step: line " + originalLine + " -> " + newLine);

        session.resume();
    }

    @Test
    void boxedPrimitive_rendersAsPrimitive() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);

        List<String> captures = List.of("this.boxedCount");
        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null,
                captures, "A", false, null, null, null, null, null);
        assertNull(bp.get("error"), "bp failed: " + bp);

        List<Hit> hits = pollHits(session, (String) bp.get("breakpointId"), 1, 15);
        Hit h0 = hits.get(0);
        CaptureResult cr = h0.captures.get(0);
        assertEquals("ok", cr.status, "boxedCount should resolve: " + cr.status);
        assertEquals("primitive", cr.value.kind, "boxed Integer should render as primitive, got: " + cr.value.kind);
        assertEquals(42, cr.value.value, "boxedCount=" + cr.value.value);
        System.out.println("[test] boxed primitive: kind=" + cr.value.kind + " value=" + cr.value.value);
    }

    @Test
    void safeMode_disablesGetterButAllowsField() throws Exception {
        startParams.safeMode = true;
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);

        // Capture both a getter (should fail) and a field (should work)
        List<String> captures = List.of("this.getServiceName()", "this.serviceName");
        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null,
                captures, "A", false, null, null, null, null, null);
        assertNull(bp.get("error"), "bp failed: " + bp);

        List<Hit> hits = pollHits(session, (String) bp.get("breakpointId"), 1, 15);
        Hit h0 = hits.get(0);
        Map<String, CaptureResult> byExpr = new java.util.HashMap<>();
        for (CaptureResult cr : h0.captures) byExpr.put(cr.expr, cr);

        // getter should fail in safe mode
        CaptureResult getter = byExpr.get("this.getServiceName()");
        assertNotNull(getter);
        assertEquals("not_found", getter.status, "getter should fail in safe_mode");
        assertTrue(getter.error.contains("safe_mode"), "error should mention safe_mode: " + getter.error);

        // field should work
        CaptureResult field = byExpr.get("this.serviceName");
        assertNotNull(field);
        assertEquals("ok", field.status);
        assertEquals("userService", field.value.value);

        System.out.println("[test] safe_mode: getter=" + getter.status + " field=" + field.status);
        startParams.safeMode = null; // reset for other tests
    }

    @Test
    void configure_raisesBudgetAndShowsEffect() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);

        // Raise explore budget
        Map<String, Object> cfg = session.configure(null, null, null, null, null, null,
                10, null, null, null, null);
        assertNull(cfg.get("error"), "configure failed: " + cfg);
        @SuppressWarnings("unchecked")
        Map<String, Object> budgets = (Map<String, Object>) cfg.get("budgets");
        assertEquals(10, budgets.get("explore"), "explore budget should be 10");
        // Should warn about raising above default
        assertNotNull(cfg.get("warning"), "should warn when raising above default");
        System.out.println("[test] configure: explore=" + budgets.get("explore") + " warning=" + cfg.get("warning"));
    }

    @Test
    void listDebuggableJvms_returnsProcesses() throws Exception {
        // Launch a target first so jps has something to find
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);

        Map<String, Object> jvms = session.listDebuggableJvms();
        assertNull(jvms.get("error"), "list_debuggable_jvms failed: " + jvms);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> procs = (List<Map<String, Object>>) jvms.get("processes");
        assertNotNull(procs);
        assertTrue(procs.size() > 0, "should find at least jps itself");

        System.out.println("[test] list_debuggable_jvms: " + procs.size() + " processes");
        // jps may not show full args for child processes in test environments;
        // the important thing is the method runs without errors.
        for (Map<String, Object> p : procs) {
            System.out.println("[test]   pid=" + p.get("pid") + " main=" + p.get("mainClass")
                    + " jdwp=" + p.get("jdwp"));
        }
        // Note: in production, attachable JVMs will show jdwp info correctly.
        // The test environment (surefire fork + child process) may obscure args.
    }

    @Test
    void removeBreakpoint_clearsHitsAndStatus() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);

        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null,
                List.of("this.serviceName"), "A", false, null, null, null, null, null);
        assertNull(bp.get("error"), "bp failed: " + bp);
        String bpId = (String) bp.get("breakpointId");
        List<Hit> hits = pollHits(session, bpId, 1, 15);
        assertFalse(hits.isEmpty(), "expected at least 1 hit before remove");

        Map<String, Object> st = session.sessionStatus();
        assertTrue(((Number) st.get("hitBufferSize")).intValue() > 0, "hitBufferSize>0 before remove: " + st);

        Map<String, Object> rem = session.removeBreakpoint(bpId);
        assertNull(rem.get("error"), "remove failed: " + rem);
        assertEquals("removed", rem.get("status"));
        assertTrue(((Number) rem.get("clearedHits")).intValue() > 0, "should clear hits: " + rem);

        @SuppressWarnings("unchecked")
        List<Hit> after = (List<Hit>) session.listHits(bpId, null, null, null, null).get("hits");
        assertTrue(after == null || after.isEmpty(), "hits should be cleared after remove: " + after);

        Map<String, Object> st2 = session.sessionStatus();
        assertEquals(0, ((Number) st2.get("hitBufferSize")).intValue(),
                "hitBufferSize should be 0 (consistent with list_hits) after remove: " + st2);
        System.out.println("[test] remove_breakpoint cleared " + rem.get("clearedHits") + " hits; status hitBufferSize=" + st2.get("hitBufferSize"));
    }

    @Test
    void listClasses_substringFilter() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);
        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null,
                List.of("this.serviceName"), "A", false, null, null, null, null, null);
        pollHits(session, (String) bp.get("breakpointId"), 1, 10);

        Map<String, Object> lc = session.listClasses("UserService", false, 100, 0);
        assertNull(lc.get("error"), "list_classes failed: " + lc);
        @SuppressWarnings("unchecked")
        List<?> cands = (List<?>) lc.get("candidates");
        assertNotNull(cands);
        assertTrue(cands.size() > 0, "filter 'UserService' should match at least UserService: " + cands);
        assertTrue(cands.stream().allMatch(c -> c.toString().toLowerCase().contains("userservice")),
                "all results must match the substring filter (no garbage): " + cands);
        System.out.println("[test] list_classes filter 'UserService' -> " + cands.size() + " matches");
    }

    @Test
    void shallowBareRoot_rendersDirectFieldsOnly() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);
        // args[1] is an Order (plain object) with an `items` List field. Bare root -> shallow:
        // direct fields shown, but the items List must NOT expand (toString summary).
        List<String> captures = List.of("args[1]");
        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null,
                captures, "A", false, null, null, null, null, null);
        assertNull(bp.get("error"), "bp failed: " + bp);
        List<Hit> hits = pollHits(session, (String) bp.get("breakpointId"), 1, 15);
        Hit h0 = hits.get(0);
        CaptureResult cr = h0.captures.get(0);
        assertEquals("ok", cr.status, "args[1] should resolve: " + cr.status);
        assertEquals("object", cr.value.kind, "bare Order should render as object");
        assertNotNull(cr.value.fields.get("id"), "should have id field");
        ValueNode itemsNode = cr.value.fields.get("items");
        assertNotNull(itemsNode, "should have items field");
        assertNotEquals("array", itemsNode.kind,
                "shallow render: items List must NOT expand (got kind=" + itemsNode.kind + ")");
        System.out.println("[test] shallow args[1]: id=" + cr.value.fields.get("id").value
                + " items.kind=" + itemsNode.kind + " (not array = shallow OK)");
    }

    @Test
    void renderBudget_truncatesOversizedCapture() throws Exception {
        startParams.maxRenderNodes = 3;
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);
        // args[1].items is a 25-element List; with a 3-node budget it must truncate early.
        List<String> captures = List.of("args[1].items");
        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null,
                captures, "A", false, null, null, null, null, null);
        assertNull(bp.get("error"), "bp failed: " + bp);
        List<Hit> hits = pollHits(session, (String) bp.get("breakpointId"), 1, 15);
        Hit h0 = hits.get(0);
        CaptureResult cr = h0.captures.get(0);
        assertEquals("ok", cr.status);
        assertEquals("array", cr.value.kind);
        assertEquals(Boolean.TRUE, cr.value.truncated, "low render budget should truncate: " + cr.value.truncated);
        assertTrue(cr.value.elements.size() < 20, "budget should limit elements: got " + cr.value.elements.size());
        System.out.println("[test] render budget: elements=" + cr.value.elements.size() + " truncated=" + cr.value.truncated);
    }

    @Test
    void arrayLength_capturesLength() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);
        // sampleArray is int[]{10, 20, 30, 40, 50}
        List<String> captures = List.of("this.sampleArray.length");
        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null,
                captures, "A", false, null, null, null, null, null);
        assertNull(bp.get("error"), "bp failed: " + bp);
        List<Hit> hits = pollHits(session, (String) bp.get("breakpointId"), 1, 15);
        Hit h0 = hits.get(0);
        CaptureResult cr = h0.captures.get(0);
        assertEquals("ok", cr.status, "array.length should resolve: " + cr.status);
        assertEquals("primitive", cr.value.kind, "length should be primitive, got: " + cr.value.kind);
        assertEquals(5, cr.value.value, "sampleArray.length should be 5, got: " + cr.value.value);
        System.out.println("[test] array.length=" + cr.value.value);
    }

    @Test
    void varIndex_resolvesFromLocalVariable() throws Exception {
        Map<String, Object> start = session.start(startParams);
        assertNull(start.get("error"), "start failed: " + start);
        // mid=2 declared at line 23; break at line 25 (after mid is in scope).
        // sampleArray[mid] = sampleArray[2] = 30
        List<String> captures = List.of("this.sampleArray[mid]");
        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", 25,
                captures, "A", false, null, null, null, null, null);
        assertNull(bp.get("error"), "bp failed: " + bp);
        List<Hit> hits = pollHits(session, (String) bp.get("breakpointId"), 1, 15);
        Hit h0 = hits.get(0);
        CaptureResult cr = h0.captures.get(0);
        assertEquals("ok", cr.status, "[mid] should resolve: " + cr.status
                + (cr.error != null ? " " + cr.error : ""));
        assertEquals("primitive", cr.value.kind, "array element should be primitive, got: " + cr.value.kind);
        assertEquals(30, cr.value.value, "sampleArray[2] should be 30, got: " + cr.value.value);
        System.out.println("[test] sampleArray[mid]=" + cr.value.value);
    }

    // ---- helpers ----
    private List<Hit> pollHits(DebugSession s, String bpId, int expected, int timeoutSec) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        List<Hit> hits = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            @SuppressWarnings("unchecked")
            List<Hit> cur = (List<Hit>) s.listHits(bpId, null, null, null, null).get("hits");
            if (cur != null && cur.size() >= expected) {
                hits = cur;
                break;
            }
            Thread.sleep(150);
        }
        if (hits.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Hit> cur = (List<Hit>) s.listHits(bpId, null, null, null, null).get("hits");
            hits = cur == null ? List.of() : cur;
        }
        return hits;
    }

    private static void assumeExists(Path p) {
        Assumptions.assumeTrue(Files.exists(p),
                "demo jar not found at " + p + " — run `mvn -pl demo package` first");
    }
}

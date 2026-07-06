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
    private Config config;

    @BeforeEach
    void setUp() throws Exception {
        Path demoJar = Path.of("F:/project/java-debug/demo/target/demo.jar");
        if (!Files.exists(demoJar)) {
            demoJar = Path.of("../demo/target/demo.jar");
        }
        assumeExists(demoJar);

        config = new Config();
        config.target.classpath = List.of(demoJar.toAbsolutePath().toString());
        config.target.mainClass = "com.ylw.demo.DemoApp";
        config.target.suspend = true;
        config.target.workingDir = Path.of("F:/project/java-debug").toAbsolutePath().toString();
        config.debug.host = "127.0.0.1";
        config.debug.port = 0;
        config.debug.captureDepth = 5;
        config.debug.toStringLimit = 500;
        config.debug.collectionLimit = 20;
        config.debug.maxHits = 1000;
        config.debug.modeB.exploreBudget = 5;
        config.debug.modeB.evalBudget = 2;
        config.debug.modeB.defaultTimeoutSec = 30;

        session = new DebugSession(config);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            try { session.stop(); } catch (Throwable ignored) {}
        }
    }

    @Test
    void modeA_capturesNullBreakAndCollectionTruncation() throws Exception {
        Map<String, Object> start = session.start(null, null);
        assertNull(start.get("error"), "start failed: " + start);
        System.out.println("[test] started: " + start);

        List<String> captures = List.of(
                "args[0].name",
                "args[0].address.id",
                "args[0].getAddress().getId()",
                "args[1].items[0].sku",
                "args[1].items",
                "this.serviceName",
                "this.callCount"
        );

        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null, captures, "A", false, null);
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

        // hit counting: 3 hits with ordinals 1,2,3
        assertEquals(3, hits.size());
        assertEquals(1, hits.get(0).hitOrdinal);
        assertEquals(2, hits.get(1).hitOrdinal);
        assertEquals(3, hits.get(2).hitOrdinal);

        System.out.println("[test] mode A assertions passed");
    }

    @Test
    void modeB_blocksReturnsHitAndExploreResume() throws Exception {
        Map<String, Object> start = session.start(null, null);
        assertNull(start.get("error"), "start failed: " + start);

        List<String> captures = List.of("args[0].name", "this.serviceName");
        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null, captures, "B", false, 20);
        assertNull(bp.get("error"), "set_breakpoint mode B failed: " + bp);
        assertEquals("hit", bp.get("status"), "mode B should return hit: " + bp);
        assertEquals(Boolean.TRUE, bp.get("jvmSuspended"));

        Hit hit = (Hit) bp.get("hit");
        assertNotNull(hit);
        assertEquals("B", hit.mode);

        // explore while suspended
        Map<String, Object> exp = session.explore("args[0].address.id");
        assertNull(exp.get("error"), "explore failed: " + exp);
        CaptureResult expResult = (CaptureResult) exp.get("result");
        assertEquals("null_break", expResult.status);
        assertEquals("address", expResult.nullBreakAt);
        System.out.println("[test] explore result: " + exp);

        // resume
        Map<String, Object> r = session.resume();
        assertNull(r.get("error"), "resume failed: " + r);
        assertEquals("resumed", r.get("status"));
        System.out.println("[test] mode B + explore + resume passed");
    }

    @Test
    void listClasses_resolvesUserService() throws Exception {
        Map<String, Object> start = session.start(null, null);
        assertNull(start.get("error"), "start failed: " + start);
        // The demo references UserService; it should be loaded after the target runs a bit.
        // With suspend=y the target is paused at start, so classes may not be loaded yet.
        // Resume briefly to let it load, then check.
        Map<String, Object> bp = session.setBreakpoint("UserService", "processUser", null, List.of("this.serviceName"), "A", false, null);
        assertNull(bp.get("error"), "bp failed: " + bp);
        pollHits(session, (String) bp.get("breakpointId"), 1, 10);

        Map<String, Object> cls = session.listClasses("UserService", false);
        assertNull(cls.get("error"), "list_classes failed: " + cls);
        List<?> cands = (List<?>) cls.get("candidates");
        assertNotNull(cands);
        assertTrue(cands.stream().anyMatch(c -> c.toString().contains("com.ylw.demo.UserService")),
                "expected com.ylw.demo.UserService in candidates: " + cands);
        System.out.println("[test] list_classes OK: " + cands);
    }

    // ---- helpers ----
    private List<Hit> pollHits(DebugSession s, String bpId, int expected, int timeoutSec) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        List<Hit> hits = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            @SuppressWarnings("unchecked")
            List<Hit> cur = (List<Hit>) s.listHits(bpId, null, null).get("hits");
            if (cur != null && cur.size() >= expected) {
                hits = cur;
                break;
            }
            Thread.sleep(150);
        }
        if (hits.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Hit> cur = (List<Hit>) s.listHits(bpId, null, null).get("hits");
            hits = cur == null ? List.of() : cur;
        }
        return hits;
    }

    private static void assumeExists(Path p) {
        Assumptions.assumeTrue(Files.exists(p),
                "demo jar not found at " + p + " — run `mvn -pl demo package` first");
    }
}

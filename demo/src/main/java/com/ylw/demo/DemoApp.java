package com.ylw.demo;

/**
 * Debug target demo. Exercises the scenarios the JDB-MCP tool must handle:
 *  - nested object path / field read          (args[0].name)
 *  - null-break at an intermediate segment      (args[0].address.id, address is null)
 *  - null-break at a getter                     (args[0].getAddress().getId())
 *  - array/collection index + truncation        (args[1].items[0].sku, args[1].items with 25 items)
 *  - deep path limit + cycle prevention         (sumChain on a cyclic list)
 *  - this-field read                            (this.serviceName, this.callCount)
 *
 * Run with suspend=y so the debugger attaches before main() proceeds.
 */
public class DemoApp {

    public static void main(String[] args) throws Exception {
        UserService svc = new UserService();

        // Scenario A/B: processUser hit 3 times -> hit counting + ring buffer.
        for (int i = 1; i <= 3; i++) {
            User u = svc.findById(i);                 // address == null
            Order order = svc.createOrder(i, 25);     // 25 items -> collection truncation
            svc.processUser(u, order);
            Thread.sleep(200);
        }

        // Scenario C: cyclic linked list -> deep-path limit + cycle prevention.
        Node head = buildCyclicChain(10);
        svc.sumChain(head);

        // Scenario D: throws an exception -> exception breakpoint testing.
        try {
            svc.processFailing();
        } catch (DemoException e) {
            System.out.println("[demo] caught exception: " + e.getMessage());
        }

        System.out.println("[demo] done. callCount=" + svc.getCallCount());
    }

    private static Node buildCyclicChain(int n) {
        Node head = new Node(0, null);
        Node cur = head;
        for (int i = 1; i < n; i++) {
            cur.setNext(new Node(i, null));
            cur = cur.getNext();
        }
        cur.setNext(head); // close the cycle
        return head;
    }
}

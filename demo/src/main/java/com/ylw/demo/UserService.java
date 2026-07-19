package com.ylw.demo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class UserService {

    private int callCount = 0;
    private String serviceName = "userService";
    private Integer boxedCount = 42;   // for boxed primitive test
    private final int[] sampleArray = {10, 20, 30, 40, 50}; // for array.length + [var] index tests
    private int sampleIndex = 2;

    /** Returns a user whose address is null — triggers null-break on user.address.id. */
    public User findById(long id) {
        callCount++;
        return new User(id, "user-" + id, null);
    }

    /** Breakpoint target: args[0]=User (address null), args[1]=Order (25 items). */
    public void processUser(User user, Order order) {
        int mid = 2;  // for [var] index test (must be before callCount++ so it's visible at method entry bp)
        callCount++;
        String name = user.getName();
        int itemCount = order == null ? 0 : order.getItems().size();
        System.out.println("[demo] processUser name=" + name + " items=" + itemCount);
    }

    public Order createOrder(long id, int itemCount) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(new Item("SKU-" + i, new BigDecimal(i + ".99")));
        }
        return new Order(id, items);
    }

    /** Breakpoint target for deep-path and cycle tests: a cyclic linked list. */
    public int sumChain(Node head) {
        callCount++;
        int s = 0;
        Node c = head;
        int guard = 0;
        while (c != null && guard++ < 1000) {
            s += c.getValue();
            c = c.getNext();
        }
        return s;
    }

    /** Throws a DemoException for testing exception breakpoints. */
    public void processFailing() {
        callCount++;
        throw new DemoException("test exception for breakpoint");
    }

    public int getCallCount() { return callCount; }
    public String getServiceName() { return serviceName; }
}

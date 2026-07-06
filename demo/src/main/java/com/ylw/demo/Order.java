package com.ylw.demo;

import java.util.Collections;
import java.util.List;

public class Order {
    private final long id;
    private final List<Item> items;

    public Order(long id, List<Item> items) {
        this.id = id;
        this.items = items;
    }

    public long getId() { return id; }
    public List<Item> getItems() { return Collections.unmodifiableList(items); }

    @Override
    public String toString() {
        return "Order{id=" + id + ", items=" + items.size() + "}";
    }
}

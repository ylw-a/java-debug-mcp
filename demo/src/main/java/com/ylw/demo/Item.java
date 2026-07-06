package com.ylw.demo;

import java.math.BigDecimal;

public class Item {
    private final String sku;
    private final BigDecimal price;

    public Item(String sku, BigDecimal price) {
        this.sku = sku;
        this.price = price;
    }

    public String getSku() { return sku; }
    public BigDecimal getPrice() { return price; }

    @Override
    public String toString() {
        return "Item{sku='" + sku + "', price=" + price + "}";
    }
}

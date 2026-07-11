package com.ylw.demo;

/** Custom exception for testing exception breakpoints. */
public class DemoException extends RuntimeException {
    private final String reason;

    public DemoException(String reason) {
        super("demo error: " + reason);
        this.reason = reason;
    }

    public String getReason() { return reason; }
}
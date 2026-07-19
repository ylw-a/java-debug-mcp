package com.ylw.jdbmcp;

import com.ylw.jdbmcp.debug.ExprCapture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Parser-only unit tests (no JVM required). Resolution/rendering is covered by DebugSessionIT. */
class ExprCaptureTest {

    @Test
    void parsesValidExpressions() {
        assertTrue(ExprCapture.validate("this").isEmpty());
        assertTrue(ExprCapture.validate("this.id").isEmpty());
        assertTrue(ExprCapture.validate("user.id").isEmpty());
        assertTrue(ExprCapture.validate("user.address.id").isEmpty());
        assertTrue(ExprCapture.validate("user.getAddress().getId()").isEmpty());
        assertTrue(ExprCapture.validate("args[0]").isEmpty());
        assertTrue(ExprCapture.validate("args[0].name").isEmpty());
        assertTrue(ExprCapture.validate("args[1].items[0].sku").isEmpty());
        assertTrue(ExprCapture.validate("head.next.next.next.next.next").isEmpty());
        assertTrue(ExprCapture.validate("this.getService().getId()").isEmpty());
        assertTrue(ExprCapture.validate("data.length").isEmpty(), "array.length");
        assertTrue(ExprCapture.validate("sorted[mid]").isEmpty(), "var index");
        assertTrue(ExprCapture.validate("args[0][mid]").isEmpty(), "nested index");
    }

    @Test
    void rejectsMalformedExpressions() {
        assertFalse(ExprCapture.validate("user..id").isEmpty(), "double dot");
        assertFalse(ExprCapture.validate("args[]").isEmpty(), "empty index");
        assertFalse(ExprCapture.validate("this.").isEmpty(), "trailing dot");
        assertFalse(ExprCapture.validate("user.getAddress(arg)").isEmpty(), "getter with arg");
        assertFalse(ExprCapture.validate("user.address.").isEmpty(), "trailing dot after field");
        assertFalse(ExprCapture.validate("args[1.5]").isEmpty(), "float index");
        assertFalse(ExprCapture.validate("").isEmpty(), "empty");
    }

    @Test
    void handlesWhitespace() {
        assertTrue(ExprCapture.validate("  this.id  ").isEmpty());
        assertTrue(ExprCapture.validate("args[ 0 ]").isEmpty());
        assertTrue(ExprCapture.validate("sorted[ mid ]").isEmpty(), "var index with whitespace");
    }
}

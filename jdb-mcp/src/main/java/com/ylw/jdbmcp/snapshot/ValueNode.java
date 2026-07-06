package com.ylw.jdbmcp.snapshot;

import java.util.List;
import java.util.Map;

/**
 * Rendered form of a JDI {@code Value}. Pure POJO - no JDI references - so it can
 * safely cross from the JDI event thread to the MCP handler thread.
 *
 * <p>{@code kind} discriminates the shape:
 * <ul>
 *   <li>{@code primitive} - {@code value} holds the literal (number/boolean).</li>
 *   <li>{@code string}    - {@code value} holds the (truncated) text.</li>
 *   <li>{@code null}      - the value was null at this point.</li>
 *   <li>{@code object}    - {@code fields} holds named child nodes.</li>
 *   <li>{@code array}     - {@code elements} holds child nodes (first N).</li>
 *   <li>{@code toString}  - depth exhausted; {@code toStringValue} holds a truncated toString().</li>
 * </ul>
 */
public class ValueNode {

    public String kind;
    public String type;
    public Object value;
    public Map<String, ValueNode> fields;
    public List<ValueNode> elements;
    public Boolean truncated;
    public Integer totalElements;
    public String toStringValue;

    public static ValueNode primitive(String type, Object v) {
        ValueNode n = new ValueNode();
        n.kind = "primitive";
        n.type = type;
        n.value = v;
        return n;
    }

    public static ValueNode stringVal(String type, String v) {
        ValueNode n = new ValueNode();
        n.kind = "string";
        n.type = type;
        n.value = v;
        return n;
    }

    public static ValueNode nullVal(String type) {
        ValueNode n = new ValueNode();
        n.kind = "null";
        n.type = type;
        return n;
    }

    public static ValueNode object(String type, Map<String, ValueNode> fields) {
        ValueNode n = new ValueNode();
        n.kind = "object";
        n.type = type;
        n.fields = fields;
        return n;
    }

    public static ValueNode array(String type, List<ValueNode> elements, boolean truncated, int totalElements) {
        ValueNode n = new ValueNode();
        n.kind = "array";
        n.type = type;
        n.elements = elements;
        n.truncated = truncated;
        n.totalElements = totalElements;
        return n;
    }

    public static ValueNode toStringNode(String type, String toStringValue) {
        ValueNode n = new ValueNode();
        n.kind = "toString";
        n.type = type;
        n.toStringValue = toStringValue;
        return n;
    }
}

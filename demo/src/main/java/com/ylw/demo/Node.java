package com.ylw.demo;

/** Linked-list node; used to exercise path-depth limit and cycle prevention. */
public class Node {
    private final int value;
    private Node next;

    public Node(int value, Node next) {
        this.value = value;
        this.next = next;
    }

    public int getValue() { return value; }
    public Node getNext() { return next; }

    public void setNext(Node next) { this.next = next; }

    @Override
    public String toString() {
        return "Node{value=" + value + "}";
    }
}

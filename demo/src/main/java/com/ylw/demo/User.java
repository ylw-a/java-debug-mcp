package com.ylw.demo;

public class User {
    private long id;
    private String name;
    private Address address;

    public User(long id, String name, Address address) {
        this.id = id;
        this.name = name;
        this.address = address;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public Address getAddress() { return address; }

    public void setId(long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setAddress(Address address) { this.address = address; }

    @Override
    public String toString() {
        return "User{id=" + id + ", name='" + name + "', address=" + address + "}";
    }
}

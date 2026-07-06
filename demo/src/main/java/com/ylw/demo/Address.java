package com.ylw.demo;

public class Address {
    private final long id;
    private final String city;

    public Address(long id, String city) {
        this.id = id;
        this.city = city;
    }

    public long getId() { return id; }
    public String getCity() { return city; }

    @Override
    public String toString() {
        return "Address{id=" + id + ", city='" + city + "'}";
    }
}

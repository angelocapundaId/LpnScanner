package com.empresa.lpnscanner;

public class Operator {
    public String id;
    public String name;
    public boolean active;

    public Operator() {} // Firestore

    public Operator(String id, String name, boolean active) {
        this.id = id;
        this.name = name;
        this.active = active;
    }
}

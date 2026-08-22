package com.orderup.orders;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Tiny key/value store for runtime toggles that must survive a JVM restart.
 * Currently used for the "Disable orders" flag so that a mid-market restart
 * defaults to the operator's last explicit choice, not to "orders enabled".
 */
@Entity
@Table(name = "runtime_flag")
public class RuntimeFlag {

    @Id
    @Column(name = "flag_name")
    private String name;

    @Column(name = "flag_value")
    private String value;
    private Instant updatedAt;

    public RuntimeFlag() {}

    public RuntimeFlag(String name, String value) {
        this.name = name;
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public String  getName()      { return name; }
    public String  getValue()     { return value; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setValue(String value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }
}


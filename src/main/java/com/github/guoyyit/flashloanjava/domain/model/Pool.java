package com.github.guoyyit.flashloanjava.domain.model;

public class Pool {

    private String id;
    private String name;
    private java.util.TreeMap<String, java.math.BigDecimal> balances = new java.util.TreeMap<>();

    public Pool() {
    }

    public Pool(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public java.util.TreeMap<String, java.math.BigDecimal> getBalances() {
        return balances;
    }

    public void setBalances(java.util.TreeMap<String, java.math.BigDecimal> balances) {
        this.balances = balances == null ? new java.util.TreeMap<>() : balances;
    }
}

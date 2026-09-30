package com.github.guoyyit.flashloanjava.web.dto;

import java.math.BigDecimal;
import java.util.TreeMap;

public class CreatePoolRequest {

    private String id;
    private String name;
    private TreeMap<String, BigDecimal> balances;

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

    public TreeMap<String, BigDecimal> getBalances() {
        return balances;
    }

    public void setBalances(TreeMap<String, BigDecimal> balances) {
        this.balances = balances;
    }
}

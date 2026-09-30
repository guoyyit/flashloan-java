package com.github.guoyyit.flashloanjava.web.dto;

import java.math.BigDecimal;
import java.util.TreeMap;

import com.github.guoyyit.flashloanjava.domain.model.Pool;

public class PoolView {

    private String id;
    private String name;
    private TreeMap<String, BigDecimal> balances;

    public static PoolView from(Pool pool) {
        PoolView view = new PoolView();
        view.id = pool.getId();
        view.name = pool.getName();
        view.balances = new TreeMap<>(pool.getBalances());
        return view;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public TreeMap<String, BigDecimal> getBalances() {
        return balances;
    }
}

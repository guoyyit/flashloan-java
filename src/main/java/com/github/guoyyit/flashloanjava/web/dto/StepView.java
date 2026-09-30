package com.github.guoyyit.flashloanjava.web.dto;

import java.math.BigDecimal;

import com.github.guoyyit.flashloanjava.domain.model.LoanRecord;
import com.github.guoyyit.flashloanjava.domain.model.OperationType;

public class StepView {

    private int index;
    private OperationType type;
    private String fromPool;
    private String toPool;
    private String currency;
    private BigDecimal amount;
    private String fromCurrency;
    private String toCurrency;
    private BigDecimal fromAmount;
    private BigDecimal toAmount;
    private BigDecimal fee;

    public static StepView from(LoanRecord.Step step) {
        StepView view = new StepView();
        view.index = step.getIndex();
        view.type = step.getType();
        view.fromPool = step.getFromPool();
        view.toPool = step.getToPool();
        view.currency = step.getCurrency();
        view.amount = step.getAmount();
        view.fromCurrency = step.getFromCurrency();
        view.toCurrency = step.getToCurrency();
        view.fromAmount = step.getFromAmount();
        view.toAmount = step.getToAmount();
        view.fee = step.getFee();
        return view;
    }

    public int getIndex() {
        return index;
    }

    public OperationType getType() {
        return type;
    }

    public String getFromPool() {
        return fromPool;
    }

    public String getToPool() {
        return toPool;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getFromCurrency() {
        return fromCurrency;
    }

    public String getToCurrency() {
        return toCurrency;
    }

    public BigDecimal getFromAmount() {
        return fromAmount;
    }

    public BigDecimal getToAmount() {
        return toAmount;
    }

    public BigDecimal getFee() {
        return fee;
    }
}

package com.github.guoyyit.flashloanjava.web.dto;

import java.math.BigDecimal;

import com.github.guoyyit.flashloanjava.domain.model.OperationType;

public class OperationSpec {

    private OperationType type;
    /** SWAP 兑换发生的资金池；TRANSFER 不使用。 */
    private String pool;
    private String fromPool;
    private String toPool;
    private String currency;
    private BigDecimal amount;
    private String fromCurrency;
    private String toCurrency;
    private BigDecimal fromAmount;
    private BigDecimal toAmount;

    public OperationType getType() {
        return type;
    }

    public void setType(OperationType type) {
        this.type = type;
    }

    public String getPool() {
        return pool;
    }

    public void setPool(String pool) {
        this.pool = pool;
    }

    public String getFromPool() {
        return fromPool;
    }

    public void setFromPool(String fromPool) {
        this.fromPool = fromPool;
    }

    public String getToPool() {
        return toPool;
    }

    public void setToPool(String toPool) {
        this.toPool = toPool;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getFromCurrency() {
        return fromCurrency;
    }

    public void setFromCurrency(String fromCurrency) {
        this.fromCurrency = fromCurrency;
    }

    public String getToCurrency() {
        return toCurrency;
    }

    public void setToCurrency(String toCurrency) {
        this.toCurrency = toCurrency;
    }

    public BigDecimal getFromAmount() {
        return fromAmount;
    }

    public void setFromAmount(BigDecimal fromAmount) {
        this.fromAmount = fromAmount;
    }

    public BigDecimal getToAmount() {
        return toAmount;
    }

    public void setToAmount(BigDecimal toAmount) {
        this.toAmount = toAmount;
    }
}

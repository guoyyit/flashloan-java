package com.github.guoyyit.flashloanjava.web.dto;

import java.math.BigDecimal;
import java.util.List;

public class LoanRequest {

    private String poolId;
    private String currency;
    private BigDecimal amount;
    /**
     * 归还本金与手续费的方式：
     * null 表示最后一步操作必须把本金+手续费留在借款池（自动归还校验）；
     * 也可显式指定用其他币种/池兑换归还，本版本默认采用前者。
     */
    private List<OperationSpec> operations;

    public String getPoolId() {
        return poolId;
    }

    public void setPoolId(String poolId) {
        this.poolId = poolId;
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

    public List<OperationSpec> getOperations() {
        return operations;
    }

    public void setOperations(List<OperationSpec> operations) {
        this.operations = operations;
    }
}

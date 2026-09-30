package com.github.guoyyit.flashloanjava.web.dto;

import java.math.BigDecimal;

public class ConfigUpdateRequest {

    private BigDecimal feeRate;
    private BigDecimal maxLoanAmount;
    private Integer maxSteps;

    public BigDecimal getFeeRate() {
        return feeRate;
    }

    public void setFeeRate(BigDecimal feeRate) {
        this.feeRate = feeRate;
    }

    public BigDecimal getMaxLoanAmount() {
        return maxLoanAmount;
    }

    public void setMaxLoanAmount(BigDecimal maxLoanAmount) {
        this.maxLoanAmount = maxLoanAmount;
    }

    public Integer getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(Integer maxSteps) {
        this.maxSteps = maxSteps;
    }
}

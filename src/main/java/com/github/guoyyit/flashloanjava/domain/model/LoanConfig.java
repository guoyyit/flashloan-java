package com.github.guoyyit.flashloanjava.domain.model;

import java.math.BigDecimal;

public class LoanConfig {

    private long version;
    private BigDecimal feeRate;
    private BigDecimal maxLoanAmount;
    private int maxSteps;

    public LoanConfig() {
    }

    public LoanConfig(long version, BigDecimal feeRate, BigDecimal maxLoanAmount, int maxSteps) {
        this.version = version;
        this.feeRate = feeRate;
        this.maxLoanAmount = maxLoanAmount;
        this.maxSteps = maxSteps;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

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

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }
}

package com.github.guoyyit.flashloanjava.web.dto;

import java.math.BigDecimal;

import com.github.guoyyit.flashloanjava.domain.model.LoanConfig;

public class ConfigView {

    private long version;
    private BigDecimal feeRate;
    private BigDecimal maxLoanAmount;
    private int maxSteps;

    public static ConfigView from(LoanConfig config) {
        ConfigView view = new ConfigView();
        view.version = config.getVersion();
        view.feeRate = config.getFeeRate();
        view.maxLoanAmount = config.getMaxLoanAmount();
        view.maxSteps = config.getMaxSteps();
        return view;
    }

    public long getVersion() {
        return version;
    }

    public BigDecimal getFeeRate() {
        return feeRate;
    }

    public BigDecimal getMaxLoanAmount() {
        return maxLoanAmount;
    }

    public int getMaxSteps() {
        return maxSteps;
    }
}

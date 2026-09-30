package com.github.guoyyit.flashloanjava.web.dto;

import java.math.BigDecimal;
import java.util.List;

import com.github.guoyyit.flashloanjava.domain.model.FailureReason;
import com.github.guoyyit.flashloanjava.domain.model.LoanRecord;
import com.github.guoyyit.flashloanjava.domain.model.LoanStatus;

public class LoanView {

    private long id;
    private LoanStatus status;
    private String poolId;
    private String currency;
    private BigDecimal amount;
    private BigDecimal feeRate;
    private BigDecimal fee;
    private long configVersion;
    private int executedSteps;
    private FailureReason failureReason;
    private String failureMessage;
    private List<StepView> steps;

    public static LoanView from(LoanRecord record) {
        LoanView view = new LoanView();
        view.id = record.getId();
        view.status = record.getStatus();
        view.poolId = record.getPoolId();
        view.currency = record.getCurrency();
        view.amount = record.getAmount();
        view.feeRate = record.getFeeRate();
        view.fee = record.getFee();
        view.configVersion = record.getConfigVersion();
        view.executedSteps = record.getExecutedSteps();
        view.failureReason = record.getFailureReason();
        view.failureMessage = record.getFailureMessage();
        if (record.getSteps() != null) {
            view.steps = record.getSteps().stream().map(StepView::from).toList();
        }
        return view;
    }

    public long getId() {
        return id;
    }

    public LoanStatus getStatus() {
        return status;
    }

    public String getPoolId() {
        return poolId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getFeeRate() {
        return feeRate;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public long getConfigVersion() {
        return configVersion;
    }

    public int getExecutedSteps() {
        return executedSteps;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public List<StepView> getSteps() {
        return steps;
    }
}

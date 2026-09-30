package com.github.guoyyit.flashloanjava.domain.model;

import java.math.BigDecimal;
import java.util.List;

public class LoanRecord {

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
    private List<Step> steps;

    public LoanRecord() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public LoanStatus getStatus() {
        return status;
    }

    public void setStatus(LoanStatus status) {
        this.status = status;
    }

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

    public BigDecimal getFeeRate() {
        return feeRate;
    }

    public void setFeeRate(BigDecimal feeRate) {
        this.feeRate = feeRate;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public long getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(long configVersion) {
        this.configVersion = configVersion;
    }

    public int getExecutedSteps() {
        return executedSteps;
    }

    public void setExecutedSteps(int executedSteps) {
        this.executedSteps = executedSteps;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(FailureReason failureReason) {
        this.failureReason = failureReason;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public List<Step> getSteps() {
        return steps;
    }

    public void setSteps(List<Step> steps) {
        this.steps = steps;
    }

    public static class Step {

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

        public Step() {
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public OperationType getType() {
            return type;
        }

        public void setType(OperationType type) {
            this.type = type;
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

        public BigDecimal getFee() {
            return fee;
        }

        public void setFee(BigDecimal fee) {
            this.fee = fee;
        }
    }
}

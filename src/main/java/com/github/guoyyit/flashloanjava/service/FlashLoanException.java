package com.github.guoyyit.flashloanjava.service;

import com.github.guoyyit.flashloanjava.domain.model.FailureReason;

public class FlashLoanException extends RuntimeException {

    private final FailureReason reason;

    public FlashLoanException(String message) {
        super(message);
        this.reason = FailureReason.UNKNOWN_ERROR;
    }

    public FlashLoanException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public FailureReason getReason() {
        return reason;
    }
}

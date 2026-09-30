package com.github.guoyyit.flashloanjava.service;

import org.springframework.http.HttpStatus;

public class FlashLoanException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public FlashLoanException(String message, HttpStatus status) {
        super(message);
        this.status = status;
        this.code = null;
    }

    public FlashLoanException(String code, String message, HttpStatus status) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}

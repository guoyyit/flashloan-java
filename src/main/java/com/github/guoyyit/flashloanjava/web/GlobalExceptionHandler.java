package com.github.guoyyit.flashloanjava.web;

import java.time.Instant;

import com.github.guoyyit.flashloanjava.domain.model.FailureReason;
import com.github.guoyyit.flashloanjava.service.FlashLoanException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.ResponseEntity;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(FlashLoanException.class)
    public ResponseEntity<ErrorBody> handleFlashLoan(FlashLoanException e) {
        return ResponseEntity.status(statusOf(e.getReason()))
                .body(new ErrorBody(e.getReason().name(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody(FailureReason.INVALID_REQUEST.name(), "请求体格式不合法"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody(FailureReason.INVALID_REQUEST.name(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleOther(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorBody(FailureReason.UNKNOWN_ERROR.name(),
                        "服务内部错误: " + e.getMessage()));
    }

    private HttpStatus statusOf(FailureReason reason) {
        return switch (reason) {
            case POOL_NOT_FOUND, CURRENCY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INSUFFICIENT_POOL_BALANCE, INSUFFICIENT_REPAYMENT, REPAYMENT_SURPLUS ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case EXECUTION_TIMEOUT -> HttpStatus.REQUEST_TIMEOUT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    public static class ErrorBody {

        private final String status = "FAILED";
        private final String failureReason;
        private final String failureMessage;
        private final String timestamp = Instant.now().toString();

        public ErrorBody(String failureReason, String failureMessage) {
            this.failureReason = failureReason;
            this.failureMessage = failureMessage;
        }

        public String getStatus() {
            return status;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public String getFailureMessage() {
            return failureMessage;
        }

        public String getTimestamp() {
            return timestamp;
        }
    }
}

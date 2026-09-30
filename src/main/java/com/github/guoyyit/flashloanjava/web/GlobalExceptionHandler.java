package com.github.guoyyit.flashloanjava.web;

import com.github.guoyyit.flashloanjava.service.FlashLoanException;
import com.github.guoyyit.flashloanjava.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(FlashLoanException.class)
    public ResponseEntity<ErrorResponse> handleFlashLoan(FlashLoanException ex) {
        return build(ex.getStatus(), ex.getMessage(), null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, "malformed request: " + ex.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return build(status, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, String requestId) {
        ErrorResponse body = new ErrorResponse(Instant.now(), status.value(),
                status.getReasonPhrase(), message, requestId);
        return ResponseEntity.status(status).body(body);
    }
}

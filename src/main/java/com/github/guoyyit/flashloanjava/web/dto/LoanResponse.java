package com.github.guoyyit.flashloanjava.web.dto;

public record LoanResponse(String requestId,
                           String status,
                           String currency,
                           Long amount,
                           Long fee,
                           Long amountReturned,
                           Long configVersion,
                           String failureReason) {
}

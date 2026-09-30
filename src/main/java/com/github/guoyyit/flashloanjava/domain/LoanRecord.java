package com.github.guoyyit.flashloanjava.domain;

import java.time.Instant;

public record LoanRecord(String requestId,
                         long poolId,
                         String currency,
                         long amount,
                         long fee,
                         long amountReturned,
                        String status,
                        long configVersion,
                        String failureCode,
                        String failureReason,
                         Instant createdAt,
                         Instant completedAt) {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
}

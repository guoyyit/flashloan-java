package com.github.guoyyit.flashloanjava.domain;

import java.time.Instant;

public record LedgerEntry(long id,
                          String requestId,
                          long seq,
                          int step,
                          String account,
                          String currency,
                          long delta,
                          long balanceAfter,
                          String action,
                          Instant createdAt) {
}

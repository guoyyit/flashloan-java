package com.github.guoyyit.flashloanjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record ServiceConfig(long id,
                            BigDecimal feeRate,
                            long maxLoanAmount,
                            int maxSteps,
                            long timeoutMs,
                            long version,
                            Instant updatedAt) {
}

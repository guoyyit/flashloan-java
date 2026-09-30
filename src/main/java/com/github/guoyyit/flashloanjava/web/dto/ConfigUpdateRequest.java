package com.github.guoyyit.flashloanjava.web.dto;

import java.math.BigDecimal;

public record ConfigUpdateRequest(BigDecimal feeRate,
                                  Long maxLoanAmount,
                                  Integer maxSteps,
                                  Long timeoutMs) {
}

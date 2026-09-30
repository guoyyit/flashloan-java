package com.github.guoyyit.flashloanjava.web.dto;

import java.math.BigDecimal;

public record FundOperationRequest(String type,
                                   Long fromPoolId,
                                   Long toPoolId,
                                   String currency,
                                   String toCurrency,
                                   Long amount,
                                   BigDecimal rate,
                                   Long delayMs) {
}

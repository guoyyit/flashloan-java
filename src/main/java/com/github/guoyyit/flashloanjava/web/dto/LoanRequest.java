package com.github.guoyyit.flashloanjava.web.dto;

import java.util.List;

public record LoanRequest(String requestId,
                          Long poolId,
                          Long amount,
                          List<FundOperationRequest> operations,
                          Long timeoutMs,
                          Boolean crashBeforeCommit,
                          Boolean crashAfterCommit) {
}

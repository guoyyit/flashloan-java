package com.github.guoyyit.flashloanjava.domain;

import java.util.List;

public record RecoveryReport(boolean consistent,
                             int poolCount,
                             int successLoanCount,
                             int failedLoanCount,
                             List<String> problems) {
}

package com.github.guoyyit.flashloanjava.tests;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

import com.github.guoyyit.flashloanjava.domain.model.FailureReason;
import com.github.guoyyit.flashloanjava.domain.model.OperationType;
import com.github.guoyyit.flashloanjava.service.FlashLoanException;
import com.github.guoyyit.flashloanjava.service.LoanService;
import com.github.guoyyit.flashloanjava.service.PoolService;
import com.github.guoyyit.flashloanjava.web.dto.LoanRequest;
import com.github.guoyyit.flashloanjava.web.dto.OperationSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TimeoutTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("flashloan.data-dir", () -> tempDir.toString());
        registry.add("flashloan.default-fee-rate", () -> "0");
        registry.add("flashloan.execution-timeout-ms", () -> "50");
        registry.add("flashloan.test-step-delay-ms", () -> "40");
    }

    @Autowired
    private PoolService poolService;
    @Autowired
    private LoanService loanService;

    @Test
    void executionBeyondDeadlineTimesOutAndRollsBack() {
        TreeMap<String, BigDecimal> funds = new TreeMap<>();
        funds.put("USDT", new BigDecimal("1000"));
        poolService.createPool("TA", "pool-ta", funds);

        LoanRequest request = new LoanRequest();
        request.setPoolId("TA");
        request.setCurrency("USDT");
        request.setAmount(new BigDecimal("100"));
        // 两步各注入 40ms，超过 50ms 时限。
        OperationSpec first = transfer("TA", "<borrower>", "USDT", "100");
        OperationSpec second = transfer("TA", "<borrower>", "USDT", "100");
        request.setOperations(List.of(first, second));

        assertThatThrownBy(() -> loanService.executeLoan(request))
                .isInstanceOf(FlashLoanException.class)
                .extracting(e -> ((FlashLoanException) e).getReason())
                .isEqualTo(FailureReason.EXECUTION_TIMEOUT);

        // 超时整体回滚：池余额不变、无记录、无手续费。
        assertThat(poolService.getPool("TA").getBalances().get("USDT")).isEqualByComparingTo("1000");
        assertThat(loanService.listRecords()).isEmpty();
    }

    private OperationSpec transfer(String from, String to, String currency, String amount) {
        OperationSpec spec = new OperationSpec();
        spec.setType(OperationType.TRANSFER);
        spec.setFromPool(from);
        spec.setToPool(to);
        spec.setCurrency(currency);
        spec.setAmount(new BigDecimal(amount));
        return spec;
    }
}

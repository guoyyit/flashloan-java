package com.github.guoyyit.flashloanjava.tests;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

import com.github.guoyyit.flashloanjava.domain.model.LoanRecord;
import com.github.guoyyit.flashloanjava.domain.model.LoanStatus;
import com.github.guoyyit.flashloanjava.domain.model.OperationType;
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

@SpringBootTest
class FlashLoanFeeTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("flashloan.data-dir", () -> tempDir.toString());
        registry.add("flashloan.default-fee-rate", () -> "0.001");
    }

    @Autowired
    private PoolService poolService;
    @Autowired
    private LoanService loanService;

    @Test
    void feeIsCollectedAndPoolGainsExactlyTheFee() {
        TreeMap<String, BigDecimal> fundsA = new TreeMap<>();
        fundsA.put("USDT", new BigDecimal("1000"));
        poolService.createPool("A", "pool-a", fundsA);
        TreeMap<String, BigDecimal> fundsB = new TreeMap<>();
        fundsB.put("USDT", new BigDecimal("100"));
        poolService.createPool("B", "pool-b", fundsB);

        LoanRequest request = new LoanRequest();
        request.setPoolId("A");
        request.setCurrency("USDT");
        request.setAmount(new BigDecimal("1000"));
        // 从 B 池赚取 1 USDT，用于覆盖 1000 * 0.001 = 1 的手续费。
        OperationSpec earn = new OperationSpec();
        earn.setType(OperationType.TRANSFER);
        earn.setFromPool("B");
        earn.setToPool("<borrower>");
        earn.setCurrency("USDT");
        earn.setAmount(new BigDecimal("1"));
        request.setOperations(List.of(earn));

        LoanRecord record = loanService.executeLoan(request);

        assertThat(record.getStatus()).isEqualTo(LoanStatus.COMMITTED);
        assertThat(record.getFeeRate()).isEqualByComparingTo("0.001");
        assertThat(record.getFee()).isEqualByComparingTo("1");
        // A 池：借出 1000，归还 1001，净增恰好为手续费 1。
        assertThat(poolService.getPool("A").getBalances().get("USDT"))
                .isEqualByComparingTo("1001");
        // B 池被取走 1。
        assertThat(poolService.getPool("B").getBalances().get("USDT"))
                .isEqualByComparingTo("99");
    }
}

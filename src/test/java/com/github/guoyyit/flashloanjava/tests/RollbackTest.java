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
class RollbackTest {

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
    void insufficientRepaymentRollsBackEntireUnit() {
        poolService.createPool("RA", "pool-ra", balances("USDT", "1000"));

        LoanRequest request = baseLoan("RA", "USDT", "1000");
        // 不做任何操作，借款方持有 1000，但应还 1001（含 1 手续费），归还不足。
        request.setOperations(List.of());

        assertThatThrownBy(() -> loanService.executeLoan(request))
                .isInstanceOf(FlashLoanException.class)
                .extracting(e -> ((FlashLoanException) e).getReason())
                .isEqualTo(FailureReason.INSUFFICIENT_REPAYMENT);

        assertThat(poolService.getPool("RA").getBalances().get("USDT")).isEqualByComparingTo("1000");
        assertThat(loanService.listRecords()).isEmpty();
    }

    @Test
    void failedMidOperationRollsBackEarlierSteps() {
        poolService.createPool("MA", "pool-ma", balances("USDT", "1000"));
        poolService.createPool("MB", "pool-mb", balances("USDT", "100"));

        LoanRequest request = baseLoan("MA", "USDT", "1000");
        // 第一步把钱划入 B，第二步又试图从 B 取走 1000（B 只有 1100），失败。
        OperationSpec toB = transfer("<borrower>", "MB", "USDT", "1000");
        OperationSpec overdraw = transfer("MB", "<borrower>", "USDT", "1101");
        request.setOperations(List.of(toB, overdraw));

        assertThatThrownBy(() -> loanService.executeLoan(request))
                .isInstanceOf(FlashLoanException.class)
                .extracting(e -> ((FlashLoanException) e).getReason())
                .isEqualTo(FailureReason.INSUFFICIENT_POOL_BALANCE);

        // 即使第一步曾修改过 MB，也必须随整体回滚。
        assertThat(poolService.getPool("MA").getBalances().get("USDT")).isEqualByComparingTo("1000");
        assertThat(poolService.getPool("MB").getBalances().get("USDT")).isEqualByComparingTo("100");
        assertThat(loanService.listRecords()).isEmpty();
    }

    private LoanRequest baseLoan(String poolId, String currency, String amount) {
        LoanRequest request = new LoanRequest();
        request.setPoolId(poolId);
        request.setCurrency(currency);
        request.setAmount(new BigDecimal(amount));
        return request;
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

    private TreeMap<String, BigDecimal> balances(String currency, String amount) {
        TreeMap<String, BigDecimal> funds = new TreeMap<>();
        funds.put(currency, new BigDecimal(amount));
        return funds;
    }
}

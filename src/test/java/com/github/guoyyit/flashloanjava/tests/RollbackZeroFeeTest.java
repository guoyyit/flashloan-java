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
class RollbackZeroFeeTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("flashloan.data-dir", () -> tempDir.toString());
        registry.add("flashloan.default-fee-rate", () -> "0");
    }

    @Autowired
    private PoolService poolService;
    @Autowired
    private LoanService loanService;

    @Test
    void leftoverForeignCurrencyRollsBack() {
        poolService.createPool("FA", "pool-fa", balances("USDT", "1000"));
        poolService.createPool("FB", "pool-fb", balances("ETH", "5"));

        LoanRequest request = baseLoan("FA");
        // 1000 USDT 可足额归还，但借款方还额外持有 1 ETH，原子单元必须失败。
        request.setOperations(List.of(transfer("FB", "<borrower>", "ETH", "1")));

        assertThatThrownBy(() -> loanService.executeLoan(request))
                .isInstanceOf(FlashLoanException.class)
                .extracting(e -> ((FlashLoanException) e).getReason())
                .isEqualTo(FailureReason.INSUFFICIENT_REPAYMENT);

        assertThat(poolService.getPool("FB").getBalances().get("ETH")).isEqualByComparingTo("5");
        assertThat(poolService.getPool("FA").getBalances().get("USDT")).isEqualByComparingTo("1000");
        assertThat(loanService.listRecords()).isEmpty();
    }

    @Test
    void surplusRepaymentRollsBack() {
        poolService.createPool("SA", "pool-sa", balances("USDT", "1000"));
        poolService.createPool("SB", "pool-sb", balances("USDT", "100"));

        LoanRequest request = baseLoan("SA");
        // 借款 1000，又从 B 白拿 100，最终持有 1100 > 应还 1000，超额归还失败。
        request.setOperations(List.of(transfer("SB", "<borrower>", "USDT", "100")));

        assertThatThrownBy(() -> loanService.executeLoan(request))
                .isInstanceOf(FlashLoanException.class)
                .extracting(e -> ((FlashLoanException) e).getReason())
                .isEqualTo(FailureReason.REPAYMENT_SURPLUS);

        assertThat(poolService.getPool("SA").getBalances().get("USDT")).isEqualByComparingTo("1000");
        assertThat(poolService.getPool("SB").getBalances().get("USDT")).isEqualByComparingTo("100");
        assertThat(loanService.listRecords()).isEmpty();
    }

    private LoanRequest baseLoan(String poolId) {
        LoanRequest request = new LoanRequest();
        request.setPoolId(poolId);
        request.setCurrency("USDT");
        request.setAmount(new BigDecimal("1000"));
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

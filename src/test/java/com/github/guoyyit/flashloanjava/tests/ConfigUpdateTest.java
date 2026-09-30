package com.github.guoyyit.flashloanjava.tests;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

import com.github.guoyyit.flashloanjava.domain.model.FailureReason;
import com.github.guoyyit.flashloanjava.domain.model.LoanConfig;
import com.github.guoyyit.flashloanjava.domain.model.LoanRecord;
import com.github.guoyyit.flashloanjava.service.ConfigService;
import com.github.guoyyit.flashloanjava.service.FlashLoanException;
import com.github.guoyyit.flashloanjava.service.LoanService;
import com.github.guoyyit.flashloanjava.service.PoolService;
import com.github.guoyyit.flashloanjava.web.dto.LoanRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ConfigUpdateTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("flashloan.data-dir", () -> tempDir.toString());
        registry.add("flashloan.default-fee-rate", () -> "0.001");
        registry.add("flashloan.default-max-loan-amount", () -> "1000");
        registry.add("flashloan.default-max-steps", () -> "10");
    }

    @Autowired
    private PoolService poolService;
    @Autowired
    private LoanService loanService;
    @Autowired
    private ConfigService configService;

    @Test
    void runtimeUpdatesAreVersionedAndValidated() {
        long baseVersion = configService.currentConfig().getVersion();

        LoanConfig updated = configService.update(new BigDecimal("0.002"), null, null);
        assertThat(updated.getVersion()).isEqualTo(baseVersion + 1);
        assertThat(updated.getFeeRate()).isEqualByComparingTo("0.002");
        // 未提供的字段保持不变。
        assertThat(updated.getMaxLoanAmount()).isEqualByComparingTo("1000");
        assertThat(updated.getMaxSteps()).isEqualTo(10);

        LoanConfig withLimit = configService.update(null, new BigDecimal("500"), 3);
        assertThat(withLimit.getVersion()).isEqualTo(baseVersion + 2);
        assertThat(withLimit.getFeeRate()).isEqualByComparingTo("0.002");
        assertThat(withLimit.getMaxLoanAmount()).isEqualByComparingTo("500");
        assertThat(withLimit.getMaxSteps()).isEqualTo(3);

        // 非法费率必须被拒绝且不产生新版本。
        assertThatThrownBy(() -> configService.update(new BigDecimal("1.5"), null, null))
                .isInstanceOf(FlashLoanException.class)
                .extracting(e -> ((FlashLoanException) e).getReason())
                .isEqualTo(FailureReason.INVALID_REQUEST);
        assertThat(configService.currentConfig().getVersion()).isEqualTo(baseVersion + 2);

        // 恢复默认，避免影响同类中其他测试。
        configService.update(new BigDecimal("0.001"), new BigDecimal("1000"), 10);
    }

    @Test
    void updateTakesEffectOnlyForLoansStartedAfterCommit() {
        configService.update(new BigDecimal("0"), null, null);
        poolService.createPool("CA", "pool-ca", balances("USDT", "10000"));

        // 更新前开始的单元：按旧上限 1000 可借 800。
        LoanRecord before = loanService.executeLoan(loan("CA", "800"));
        assertThat(before.getStatus().name()).isEqualTo("COMMITTED");

        // 配置更新原子提交（写事务串行，更新点即为新版本生效点）。
        configService.update(null, new BigDecimal("500"), null);

        // 更新提交之后开始的单元使用新上限，800 超限被拒绝。
        assertThatThrownBy(() -> loanService.executeLoan(loan("CA", "800")))
                .isInstanceOf(FlashLoanException.class)
                .extracting(e -> ((FlashLoanException) e).getReason())
                .isEqualTo(FailureReason.LOAN_LIMIT_EXCEEDED);
        // 500 在新上限内，仍可执行。
        LoanRecord after = loanService.executeLoan(loan("CA", "500"));
        assertThat(after.getStatus().name()).isEqualTo("COMMITTED");

        // 审计记录里保留各单元实际使用的配置版本与参数，生效时点可解释。
        assertThat(before.getConfigVersion()).isLessThan(after.getConfigVersion());
        assertThat(loanService.listRecords()).hasSize(2);
    }

    private LoanRequest loan(String poolId, String amount) {
        LoanRequest request = new LoanRequest();
        request.setPoolId(poolId);
        request.setCurrency("USDT");
        request.setAmount(new BigDecimal(amount));
        request.setOperations(List.of());
        return request;
    }

    private TreeMap<String, BigDecimal> balances(String currency, String amount) {
        TreeMap<String, BigDecimal> funds = new TreeMap<>();
        funds.put(currency, new BigDecimal(amount));
        return funds;
    }
}

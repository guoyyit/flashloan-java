package com.github.guoyyit.flashloanjava.tests;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

import com.github.guoyyit.flashloanjava.domain.model.LoanRecord;
import com.github.guoyyit.flashloanjava.domain.model.LoanStatus;
import com.github.guoyyit.flashloanjava.domain.model.Pool;
import com.github.guoyyit.flashloanjava.service.ConfigService;
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

@SpringBootTest
class FlashLoanHappyPathTest {

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
    @Autowired
    private ConfigService configService;

    @Test
    void borrowAndRepayZeroFeeCommitsAtomically() {
        TreeMap<String, BigDecimal> funds = new TreeMap<>();
        funds.put("USDT", new BigDecimal("1000"));
        poolService.createPool("A", "pool-a", funds);

        LoanRequest request = new LoanRequest();
        request.setPoolId("A");
        request.setCurrency("USDT");
        request.setAmount(new BigDecimal("100"));
        // 不做任何资金操作：借款方仍持有借出的 100，由平台自动扣收归还。
        request.setOperations(List.of());

        LoanRecord record = loanService.executeLoan(request);

        assertThat(record.getStatus()).isEqualTo(LoanStatus.COMMITTED);
        assertThat(record.getFee()).isEqualByComparingTo("0");
        assertThat(record.getConfigVersion()).isEqualTo(configService.currentConfig().getVersion());
        // 零费率：借出 100 又归还 100，池余额分毫不差。
        Pool pool = poolService.getPool("A");
        assertThat(pool.getBalances().get("USDT")).isEqualByComparingTo("1000");
        // 审计记录：无用户操作，平台自动完成 1 步归还结算。
        assertThat(record.getSteps()).hasSize(1);
        assertThat(record.getSteps().get(0).getToPool()).isEqualTo("A");
    }
}

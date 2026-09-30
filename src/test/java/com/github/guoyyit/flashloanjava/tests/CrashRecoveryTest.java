package com.github.guoyyit.flashloanjava.tests;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;

import com.github.guoyyit.flashloanjava.config.FlashLoanProperties;
import com.github.guoyyit.flashloanjava.domain.model.LoanStatus;
import com.github.guoyyit.flashloanjava.service.LoanService;
import com.github.guoyyit.flashloanjava.service.PoolService;
import com.github.guoyyit.flashloanjava.storage.DurableStore;
import com.github.guoyyit.flashloanjava.storage.PersistentState;
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
class CrashRecoveryTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("flashloan.data-dir", () -> tempDir.toString());
        registry.add("flashloan.default-fee-rate", () -> "0");
    }

    @Autowired
    private PoolService poolService;
    @Autowired
    private LoanService loanService;
    @Autowired
    private DurableStore durableStore;

    @Test
    void committedSurvivesCrashButUncommittedIsRolledBack() {
        TreeMap<String, BigDecimal> funds = new TreeMap<>();
        funds.put("USDT", new BigDecimal("1000"));
        poolService.createPool("XA", "pool-xa", funds);

        // 第一个单元成功提交。
        LoanRequest first = baseLoan();
        first.setOperations(List.of());
        assertThat(loanService.executeLoan(first).getStatus()).isEqualTo(LoanStatus.COMMITTED);

        // 安排下一次提交在落盘前崩溃。
        durableStore.setCrashBeforeNextCommit();
        assertThatThrownBy(() -> loanService.executeLoan(baseLoan()))
                .isInstanceOf(DurableStore.SimulatedCrash.class);

        // 崩溃后、重启前：内存中已提交状态仍是第一笔之后的状态。
        assertThat(poolService.getPool("XA").getBalances().get("USDT")).isEqualByComparingTo("1000");
        assertThat(loanService.listRecords()).hasSize(1);

        // 模拟服务重启：用同一数据目录全新构建 DurableStore 并执行启动恢复。
        FlashLoanProperties restartProps = new FlashLoanProperties();
        restartProps.setDataDir(tempDir.toString());
        DurableStore restarted = new DurableStore(restartProps);
        restarted.init();

        PersistentState recovered = restarted.readCommitted();
        // 已提交的第一笔结果保留；崩溃的第二笔整体回滚，无记录、无手续费、无资金变化。
        assertThat(recovered.getRecords()).hasSize(1);
        assertThat(recovered.getRecords().get(0).getStatus()).isEqualTo(LoanStatus.COMMITTED);
        assertThat(recovered.getPools().get("XA").getBalances().get("USDT"))
                .isEqualByComparingTo("1000");
        assertThat(recovered.getNextLoanId()).isEqualTo(2L);

        // 恢复后下一笔将分配的 ID 紧接已提交序列（失败/崩溃单元不占用 ID），保证确定性。
        assertThat(restarted.readCommitted().getNextLoanId()).isEqualTo(2L);
    }

    private LoanRequest baseLoan() {
        LoanRequest request = new LoanRequest();
        request.setPoolId("XA");
        request.setCurrency("USDT");
        request.setAmount(new BigDecimal("100"));
        request.setOperations(List.of());
        return request;
    }
}

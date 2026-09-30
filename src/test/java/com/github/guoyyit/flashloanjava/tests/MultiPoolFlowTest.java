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
class MultiPoolFlowTest {

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
    void fundsFlowAcrossPoolsAndCurrenciesAtomically() {
        poolService.createPool("A", "pool-a", balances("USDT", "1000"));
        TreeMap<String, BigDecimal> fundsB = new TreeMap<>();
        fundsB.put("USDT", new BigDecimal("500"));
        fundsB.put("ETH", new BigDecimal("10"));
        poolService.createPool("B", "pool-b", fundsB);
        TreeMap<String, BigDecimal> fundsC = new TreeMap<>();
        fundsC.put("USDT", new BigDecimal("2000"));
        fundsC.put("ETH", new BigDecimal("0"));
        poolService.createPool("C", "pool-c", fundsC);

        LoanRequest request = new LoanRequest();
        request.setPoolId("A");
        request.setCurrency("USDT");
        request.setAmount(new BigDecimal("1000"));

        // 1) 借款 1000 USDT -> 划入 B 池
        OperationSpec toB = transfer("<borrower>", "B", "USDT", "1000");
        // 2) B 池内自兑：用池内 1000 USDT 买入 1 ETH（USDT 1500->500, ETH 10->11）
        OperationSpec swap = new OperationSpec();
        swap.setType(OperationType.SWAP);
        swap.setPool("B");
        swap.setFromPool("B");
        swap.setFromCurrency("USDT");
        swap.setToCurrency("ETH");
        swap.setFromAmount(new BigDecimal("1000"));
        swap.setToAmount(new BigDecimal("1"));
        // 3) 把买入的 1 ETH 从 B 池提到借款方（ETH 11->10）
        OperationSpec ethFromB = transfer("B", "<borrower>", "ETH", "1");
        // 4) 借款方在 C 池用 1 ETH 买入 1000 USDT（C: ETH +1, USDT 2000->1000）
        OperationSpec swapBack = new OperationSpec();
        swapBack.setType(OperationType.SWAP);
        swapBack.setPool("C");
        swapBack.setFromPool("<borrower>");
        swapBack.setFromCurrency("ETH");
        swapBack.setToCurrency("USDT");
        swapBack.setFromAmount(new BigDecimal("1"));
        swapBack.setToAmount(new BigDecimal("1000"));
        request.setOperations(List.of(toB, swap, ethFromB, swapBack));

        LoanRecord record = loanService.executeLoan(request);

        assertThat(record.getStatus()).isEqualTo(LoanStatus.COMMITTED);
        assertThat(record.getExecutedSteps()).isEqualTo(5);
        // 资金守恒（零费率）：A 收回借出款恢复原值；B 收到借款 1000 又自兑支出 1000，
        // USDT 维持初始 500，买入的 1 ETH 被提走后 ETH 维持初始 10；
        // C 用 1000 USDT 换入 1 ETH；借款方不留任何资金。
        assertThat(poolService.getPool("A").getBalances().get("USDT")).isEqualByComparingTo("1000");
        assertThat(poolService.getPool("B").getBalances().get("USDT")).isEqualByComparingTo("500");
        assertThat(poolService.getPool("B").getBalances().get("ETH")).isEqualByComparingTo("10");
        assertThat(poolService.getPool("C").getBalances().get("USDT")).isEqualByComparingTo("1000");
        assertThat(poolService.getPool("C").getBalances().get("ETH")).isEqualByComparingTo("1");
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

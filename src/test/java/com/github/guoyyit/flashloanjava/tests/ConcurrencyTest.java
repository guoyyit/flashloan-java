package com.github.guoyyit.flashloanjava.tests;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.github.guoyyit.flashloanjava.domain.model.LoanRecord;
import com.github.guoyyit.flashloanjava.domain.model.LoanStatus;
import com.github.guoyyit.flashloanjava.domain.model.OperationType;
import com.github.guoyyit.flashloanjava.service.LoanService;
import com.github.guoyyit.flashloanjava.service.PoolService;
import com.github.guoyyit.flashloanjava.storage.DurableStore;
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
class ConcurrencyTest {

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
    private DurableStore durableStore;

    @Test
    void concurrentRepaidBorrowsAreSerializedAndConsistent() throws Exception {
        durableStore.setBeforeCommitHook(null);
        TreeMap<String, BigDecimal> funds = new TreeMap<>();
        funds.put("USDT", new BigDecimal("1000"));
        poolService.createPool("PA", "pool-pa", funds);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    LoanRequest request = new LoanRequest();
                    request.setPoolId("PA");
                    request.setCurrency("USDT");
                    request.setAmount(new BigDecimal("100"));
                    request.setOperations(List.of());
                    if (loanService.executeLoan(request).getStatus() == LoanStatus.COMMITTED) {
                        success.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // 写事务全局串行化，零费率下每笔借出 100 又足额归还，20 笔全部成功，无超借。
        assertThat(success.get()).isEqualTo(threads);
        assertThat(rejected.get()).isZero();
        assertThat(poolService.getPool("PA").getBalances().get("USDT"))
                .isEqualByComparingTo("1000");
        assertThat(loanService.listRecords()).hasSize(threads);
        assertThat(loanService.listRecords())
                .allMatch(record -> record.getStatus() == LoanStatus.COMMITTED
                        && record.getFee().signum() == 0);
    }

    @Test
    void overlappingTransactionsObserveOnlyCommittedState() throws Exception {
        durableStore.setBeforeCommitHook(null);
        poolService.createPool("OA", "pool-oa", balances("USDT", "1000"));
        poolService.createPool("SINK", "sink", balances("USDT", "0"));
        poolService.createPool("RICH", "rich", balances("USDT", "100000"));

        CountDownLatch insideCommit = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean();
        AtomicReference<BigDecimal> balanceDuringFlight = new AtomicReference<>();
        AtomicInteger recordsDuringFlight = new AtomicInteger(-1);

        durableStore.setBeforeCommitHook(() -> {
            if (first.compareAndSet(false, true)) {
                insideCommit.countDown();
                Thread observer = new Thread(() -> {
                    balanceDuringFlight.set(
                            poolService.getPool("OA").getBalances().get("USDT"));
                    recordsDuringFlight.set(loanService.listRecords().size());
                    observed.countDown();
                });
                observer.start();
                try {
                    assertThat(observed.await(10, TimeUnit.SECONDS)).isTrue();
                    observer.join(10_000);
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<LoanRecord> committed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        pool.submit(() -> {
            try {
                committed.set(loanService.executeLoan(nonRepayingLoan()));
            } catch (RuntimeException e) {
                failure.set(e);
            }
        });

        assertThat(insideCommit.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        durableStore.setBeforeCommitHook(null);
        if (failure.get() != null) {
            throw new AssertionError("借款事务意外失败: " + failure.get(), failure.get());
        }
        // 进行中：并发单元只能读到提交前快照（OA 仍 1000，且看不到本单元的记录）。
        assertThat(balanceDuringFlight.get()).isEqualByComparingTo("1000");
        assertThat(loanService.listRecords()).hasSize(recordsDuringFlight.get() + 1);
        // 提交后首个事务生效：RICH 代还的 1000 被平台结算回 OA（OA 维持 1000），
        // 借出本金沉淀在 SINK，RICH 减少 1000；快照隔离使并发单元在提交前看不到中间变化。
        assertThat(committed.get().getStatus()).isEqualTo(LoanStatus.COMMITTED);
        assertThat(poolService.getPool("OA").getBalances().get("USDT"))
                .isEqualByComparingTo("1000");
        assertThat(poolService.getPool("SINK").getBalances().get("USDT"))
                .isEqualByComparingTo("1000");
        assertThat(poolService.getPool("RICH").getBalances().get("USDT"))
                .isEqualByComparingTo("99000");
    }

    private LoanRequest nonRepayingLoan() {
        LoanRequest request = new LoanRequest();
        request.setPoolId("OA");
        request.setCurrency("USDT");
        request.setAmount(new BigDecimal("1000"));
        request.setOperations(List.of(
                transfer("<borrower>", "SINK", "USDT", "1000"),
                transfer("RICH", "<borrower>", "USDT", "1000")));
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

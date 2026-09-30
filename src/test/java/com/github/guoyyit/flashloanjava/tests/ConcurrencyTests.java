package com.github.guoyyit.flashloanjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.flashloanjava.domain.Pool;
import com.github.guoyyit.flashloanjava.repository.PoolRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class ConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PoolRepository poolRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private long createPool(String currency, long deposit) throws Exception {
        String body = "{\"currency\":\"" + currency + "\",\"initialDeposit\":" + deposit + "}";
        String response = mockMvc.perform(post("/api/pools")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    @Test
    void concurrentLoansNeverDoubleSpendAvailableLiquidity() throws Exception {
        long borrowPool = createPool("USDC", 100_000);
        // Fee pool holds exactly 100, which can pay the 20 fee for only 5 units.
        long feePool = createPool("USDC", 100);

        int parallelism = 30;
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(parallelism);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < parallelism; i++) {
            final String requestId = "cc-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    String request = """
                            {
                              "requestId": "%s",
                              "poolId": %d,
                              "amount": 20000,
                              "operations": [
                                {"type":"TRANSFER","toPoolId":%d,"amount":20000,"currency":"USDC"},
                                {"type":"TRANSFER","fromPoolId":%d,"amount":20020,"currency":"USDC"},
                                {"type":"TRANSFER","toPoolId":%d,"amount":20020,"currency":"USDC"}
                              ]
                            }
                            """.formatted(requestId, borrowPool, feePool, feePool, borrowPool);
                    int code = mockMvc.perform(post("/api/loans")
                                    .contentType(MediaType.APPLICATION_JSON).content(request))
                            .andReturn().getResponse().getStatus();
                    if (code == 200) {
                        ok.incrementAndGet();
                    } else if (code == 422) {
                        rejected.incrementAndGet();
                    } else {
                        throw new IllegalStateException("unexpected status " + code);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // Exactly five units fit: five fee payments of 20 exhaust the fee pool,
        // and the principal is always returned so it never limits the count.
        assertThat(ok.get()).isEqualTo(5);
        assertThat(rejected.get()).isEqualTo(25);

        Pool borrow = poolRepository.findById(borrowPool).orElseThrow();
        Pool fee = poolRepository.findById(feePool).orElseThrow();
        assertThat(borrow.balance()).isEqualTo(100_100);
        assertThat(fee.balance()).isZero();
    }

    @Test
    void concurrentZeroFeeUnitsSerializeWithoutLostUpdates() throws Exception {
        // With a zero fee every unit restores the principal, so each racing unit
        // can succeed once serialized; the invariant is that pool and helper
        // balances end exactly where they started (no lost or phantom updates).
        long borrowPool = createPool("USDC", 30_000);
        long helper = createPool("USDC", 0);

        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"feeRate\":0}")).andExpect(status().isOk());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger ok = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            final String requestId = "cc-zero-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    String request = """
                            {
                              "requestId": "%s",
                              "poolId": %d,
                              "amount": 20000,
                              "operations": [
                                {"type":"TRANSFER","toPoolId":%d,"amount":20000,"currency":"USDC"},
                                {"type":"TRANSFER","fromPoolId":%d,"amount":20000,"currency":"USDC"},
                                {"type":"TRANSFER","toPoolId":%d,"amount":20000,"currency":"USDC"}
                              ]
                            }
                            """.formatted(requestId, borrowPool, helper, helper, borrowPool);
                    int code = mockMvc.perform(post("/api/loans")
                                    .contentType(MediaType.APPLICATION_JSON).content(request))
                            .andReturn().getResponse().getStatus();
                    if (code == 200) {
                        ok.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(2);
        assertThat(poolRepository.findById(borrowPool).orElseThrow().balance()).isEqualTo(30_000);
        assertThat(poolRepository.findById(helper).orElseThrow().balance()).isZero();
    }
}

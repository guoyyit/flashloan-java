package com.github.guoyyit.flashloanjava.tests;

import com.github.guoyyit.flashloanjava.domain.LoanRecord;
import com.github.guoyyit.flashloanjava.repository.LoanRepository;
import com.github.guoyyit.flashloanjava.repository.PoolRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class RollbackTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PoolRepository poolRepository;

    @Autowired
    private LoanRepository loanRepository;

    private long createPool(String currency, long deposit) throws Exception {
        String body = "{\"currency\":\"" + currency + "\",\"initialDeposit\":" + deposit + "}";
        String response = mockMvc.perform(post("/api/pools")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void repaymentShortfallRollsBackEveryPoolAndChargesNoFee() throws Exception {
        long borrowPool = createPool("USDC", 100_000);
        long otherPool = createPool("USDC", 0);

        // Move the borrowed money into another pool and never bring back enough.
        String request = """
                {
                  "requestId": "rb-shortfall",
                  "poolId": %d,
                  "amount": 10000,
                  "operations": [
                    {"type":"TRANSFER","toPoolId":%d,"amount":10000,"currency":"USDC"}
                  ]
                }
                """.formatted(borrowPool, otherPool);

        mockMvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "opening balance + fee")));

        // No partial writes survive: balances are exactly the opening balances.
        assertThat(poolRepository.findById(borrowPool).orElseThrow().balance()).isEqualTo(100_000);
        assertThat(poolRepository.findById(otherPool).orElseThrow().balance()).isZero();

        LoanRecord record = loanRepository.findByRequestId("rb-shortfall").orElseThrow();
        assertThat(record.status()).isEqualTo(LoanRecord.STATUS_FAILED);
        assertThat(record.fee()).isZero();
        assertThat(record.amountReturned()).isZero();
        assertThat(record.failureCode()).isEqualTo("REPAYMENT_SHORTFALL");

        // A failed unit produces no fund-flow ledger entries.
        mockMvc.perform(get("/api/loans/rb-shortfall/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void failureAtLateStepRollsBackEarlierSteps() throws Exception {
        long borrowPool = createPool("USDC", 100_000);
        long otherPool = createPool("USDC", 0);

        // Step 0 moves 5_000 out; step 1 tries to overdraw 6_000 from the pool
        // that only holds 5_000. The step-0 change must roll back as well.
        String request = """
                {
                  "requestId": "rb-late-step",
                  "poolId": %d,
                  "amount": 10000,
                  "operations": [
                    {"type":"TRANSFER","toPoolId":%d,"amount":5000,"currency":"USDC"},
                    {"type":"TRANSFER","fromPoolId":%d,"amount":6000,"currency":"USDC"}
                  ]
                }
                """.formatted(borrowPool, otherPool, otherPool);

        mockMvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "balance 5000 < required 6000")));

        assertThat(poolRepository.findById(borrowPool).orElseThrow().balance()).isEqualTo(100_000);
        assertThat(poolRepository.findById(otherPool).orElseThrow().balance()).isZero();
        assertThat(loanRepository.findByRequestId("rb-late-step").orElseThrow().fee()).isZero();
    }

    @Test
    void insufficientLiquidityIsRejectedBeforeAnyStateChange() throws Exception {
        long borrowPool = createPool("USDC", 100);
        mockMvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"rb-liquidity","poolId":%d,"amount":1000,"operations":[]}
                        """.formatted(borrowPool)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "balance 100 < loan 1000")));

        assertThat(poolRepository.findById(borrowPool).orElseThrow().balance()).isEqualTo(100);
        assertThat(loanRepository.findByRequestId("rb-liquidity").orElseThrow().failureCode())
                .isEqualTo("INSUFFICIENT_LIQUIDITY");
    }

    @Test
    void executionTimeoutRollsBackTheWholeUnitWithoutFee() throws Exception {
        long borrowPool = createPool("USDC", 100_000);
        long otherPool = createPool("USDC", 1_000);

        String request = """
                {
                  "requestId": "rb-timeout",
                  "poolId": %d,
                  "amount": 1000,
                  "timeoutMs": 50,
                  "operations": [
                    {"type":"TRANSFER","toPoolId":%d,"amount":1000,"currency":"USDC","delayMs":100},
                    {"type":"TRANSFER","fromPoolId":%d,"amount":1001,"currency":"USDC"},
                    {"type":"TRANSFER","toPoolId":%d,"amount":1001,"currency":"USDC"}
                  ]
                }
                """.formatted(borrowPool, otherPool, otherPool, borrowPool);

        mockMvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "exceeded timeout")));

        LoanRecord record = loanRepository.findByRequestId("rb-timeout").orElseThrow();
        assertThat(record.status()).isEqualTo(LoanRecord.STATUS_FAILED);
        assertThat(record.failureCode()).isEqualTo("EXECUTION_TIMEOUT");
        assertThat(record.fee()).isZero();
        assertThat(poolRepository.findById(borrowPool).orElseThrow().balance()).isEqualTo(100_000);
        assertThat(poolRepository.findById(otherPool).orElseThrow().balance()).isEqualTo(1_000);
    }
}

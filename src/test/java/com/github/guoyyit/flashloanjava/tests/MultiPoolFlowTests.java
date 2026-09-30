package com.github.guoyyit.flashloanjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class MultiPoolFlowTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PoolRepository poolRepository;

    private long createPool(String currency, long deposit) throws Exception {
        String body = "{\"currency\":\"" + currency + "\",\"initialDeposit\":" + deposit + "}";
        String response = mockMvc.perform(post("/api/pools")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    @Test
    void exchangeAcrossPoolsAndRepayWithFeeSucceedsAtomically() throws Exception {
        long usdcPool = createPool("USDC", 100_000);
        long daiPool = createPool("DAI", 0);

        // Borrow 10_000 USDC, convert to 10_100 DAI into the DAI pool, convert
        // all DAI back into USDC delivered straight to the borrow pool. Fee at
        // the default 0.1% rate is 10, so 10_100 >= 10_010 required.
        String request = """
                {
                  "requestId": "flow-success",
                  "poolId": %d,
                  "amount": 10000,
                  "operations": [
                    {"type":"EXCHANGE","toPoolId":%d,"amount":10000,"currency":"USDC","toCurrency":"DAI","rate":1.01},
                    {"type":"EXCHANGE","fromPoolId":%d,"toPoolId":%d,"amount":10100,"currency":"DAI","toCurrency":"USDC","rate":1.0}
                  ]
                }
                """.formatted(usdcPool, daiPool, daiPool, usdcPool);

        mockMvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.fee").value(10))
                .andExpect(jsonPath("$.amountReturned").value(10100));

        assertThat(poolRepository.findById(usdcPool).orElseThrow().balance()).isEqualTo(100_100);
        assertThat(poolRepository.findById(daiPool).orElseThrow().balance()).isZero();

        mockMvc.perform(get("/api/loans/flow-success/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6));

        // Deterministic replay of the same requestId returns the stored result.
        mockMvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"requestId\":\"flow-success\",\"poolId\":%d,\"amount\":10000,\"operations\":[]}"
                                .formatted(usdcPool)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.fee").value(10));

        assertThat(poolRepository.findById(usdcPool).orElseThrow().balance()).isEqualTo(100_100);
    }

    @Test
    void transferRoundTripAcrossThreePoolsRecordsFullAuditPath() throws Exception {
        long borrowPool = createPool("USDC", 50_000);
        long feePool = createPool("USDC", 1_000);

        String request = """
                {
                  "requestId": "flow-transfer",
                  "poolId": %d,
                  "amount": 20000,
                  "operations": [
                    {"type":"TRANSFER","toPoolId":%d,"amount":20000,"currency":"USDC"},
                    {"type":"TRANSFER","fromPoolId":%d,"amount":20020,"currency":"USDC"},
                    {"type":"TRANSFER","toPoolId":%d,"amount":20020,"currency":"USDC"}
                  ]
                }
                """.formatted(borrowPool, feePool, feePool, borrowPool);

        mockMvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.fee").value(20));

        JsonNode ledger = objectMapper.readTree(mockMvc.perform(get("/api/loans/flow-transfer/ledger"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ledger.size()).isEqualTo(8);
        assertThat(poolRepository.findById(borrowPool).orElseThrow().balance()).isEqualTo(50_020);
        assertThat(poolRepository.findById(feePool).orElseThrow().balance()).isEqualTo(980);
    }
}

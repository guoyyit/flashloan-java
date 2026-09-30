package com.github.guoyyit.flashloanjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class ConfigTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long createPool(String currency, long deposit) throws Exception {
        String response = mockMvc.perform(post("/api/pools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"" + currency + "\",\"initialDeposit\":" + deposit + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    @Test
    void defaultConfigExposesAllParameters() throws Exception {
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"feeRate\":0.001,\"maxLoanAmount\":1000000000,\"maxSteps\":32,\"timeoutMs\":5000}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeRate").value(0.00100000))
                .andExpect(jsonPath("$.maxLoanAmount").value(1_000_000_000))
                .andExpect(jsonPath("$.maxSteps").value(32))
                .andExpect(jsonPath("$.timeoutMs").value(5000));
    }

    private long currentVersion() throws Exception {
        String body = mockMvc.perform(get("/api/config")).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("version").asLong();
    }

    @Test
    void updateIsAtomicBumpsVersionAndAppliesToNewLoansOnly() throws Exception {
        long borrowPool = createPool("USDC", 100_000);
        long helper = createPool("USDC", 100_000);
        long versionBefore = currentVersion();

        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feeRate\":0.01,\"maxLoanAmount\":50000,\"maxSteps\":10,\"timeoutMs\":2000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(versionBefore + 1))
                .andExpect(jsonPath("$.feeRate").value(0.01000000))
                .andExpect(jsonPath("$.maxLoanAmount").value(50000))
                .andExpect(jsonPath("$.maxSteps").value(10))
                .andExpect(jsonPath("$.timeoutMs").value(2000));

        // Loan above the new limit is rejected.
        mockMvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"cfg-over-limit","poolId":%d,"amount":60000,"operations":[]}
                        """.formatted(borrowPool)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "exceeds per-loan limit")));

        // Loan within the limit uses the new 1% fee: 10000 -> fee 100.
        String request = """
                {"requestId":"cfg-new-fee","poolId":%d,"amount":10000,"operations":[
                  {"type":"TRANSFER","toPoolId":%d,"amount":10000,"currency":"USDC"},
                  {"type":"TRANSFER","fromPoolId":%d,"amount":10100,"currency":"USDC"},
                  {"type":"TRANSFER","toPoolId":%d,"amount":10100,"currency":"USDC"}]}
                """.formatted(borrowPool, helper, helper, borrowPool);
        mockMvc.perform(post("/api/loans").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").value(100))
                .andExpect(jsonPath("$.configVersion").value(versionBefore + 1));
    }

    @Test
    void partialUpdateKeepsUnspecifiedFieldsAndRejectsInvalidValues() throws Exception {
        long versionBefore = currentVersion();
        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxSteps\":16}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSteps").value(16))
                .andExpect(jsonPath("$.feeRate").value(0.00100000));

        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feeRate\":1.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("feeRate")));

        mockMvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeoutMs\":0}"))
                .andExpect(status().isBadRequest());

        // Rejected updates must not move the version or any value.
        mockMvc.perform(get("/api/config"))
                .andExpect(jsonPath("$.version").value(versionBefore + 1))
                .andExpect(jsonPath("$.maxSteps").value(16));
    }
}

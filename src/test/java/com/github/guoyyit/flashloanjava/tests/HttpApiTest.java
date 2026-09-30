package com.github.guoyyit.flashloanjava.tests;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HttpApiTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("flashloan.data-dir", () -> tempDir.toString());
        registry.add("flashloan.default-fee-rate", () -> "0");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void poolManagementLoanAuditAndConfigEndpointsWork() throws Exception {
        mockMvc.perform(post("/api/pools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"HA","name":"pool-ha","balances":{"USDT":1000}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("HA"))
                .andExpect(jsonPath("$.balances.USDT").value(1000));

        mockMvc.perform(get("/api/pools/HA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balances.USDT").value(1000));

        // 成功借款：零操作，自动归还本金；零费率下池余额不变。
        mockMvc.perform(post("/api/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poolId":"HA","currency":"USDT","amount":1000,"operations":[]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.fee").value(0))
                .andExpect(jsonPath("$.configVersion").value(1))
                .andExpect(jsonPath("$.steps[0].toPool").value("HA"));

        // 失败借款：超额归还（再从另一池白拿资金会在专门的服务测试中覆盖），
        // 这里用空池无法产生超额，改为校验借款金额非法。
        mockMvc.perform(post("/api/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poolId":"HA","currency":"USDT","amount":-5,"operations":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("INVALID_AMOUNT"));

        // 审计列表只含已提交单元（失败不产生记录）。
        mockMvc.perform(get("/api/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 零费率：池余额不变。
        mockMvc.perform(get("/api/pools/HA"))
                .andExpect(jsonPath("$.balances.USDT").value(1000));

        // 配置查询与更新。
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeRate").value(0));
        mockMvc.perform(put("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"feeRate":0.002}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.feeRate").value(0.002));
    }

    @Test
    void invalidRequestReturnsDeterministicError() throws Exception {
        mockMvc.perform(post("/api/pools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"H2","balances":{"USDT":100}}
                                """))
                .andExpect(status().isCreated());
        // 不存在的池 -> 404。
        mockMvc.perform(post("/api/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poolId":"NOPE","currency":"USDT","amount":10,"operations":[]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.failureReason").value("POOL_NOT_FOUND"));
    }

    @Test
    void failedLoanReturns422AndLeavesNoRecord() throws Exception {
        mockMvc.perform(post("/api/pools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"H3","balances":{"USDT":1000}}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/pools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"H4","balances":{"USDT":50}}
                                """))
                .andExpect(status().isCreated());

        // 借 1000 后又从 H4 白拿 50，最终持有 1050 > 应还 1000，超额归还失败（422）。
        mockMvc.perform(post("/api/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"poolId":"H3","currency":"USDT","amount":1000,"operations":[
                                  {"type":"TRANSFER","fromPool":"H4","toPool":"<borrower>",
                                   "currency":"USDT","amount":50}
                                ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failureReason").value("REPAYMENT_SURPLUS"));

        mockMvc.perform(get("/api/loans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].poolId").value("HA"));
        mockMvc.perform(get("/api/pools/H4"))
                .andExpect(jsonPath("$.balances.USDT").value(50));
    }
}

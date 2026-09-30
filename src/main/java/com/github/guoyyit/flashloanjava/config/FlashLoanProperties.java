package com.github.guoyyit.flashloanjava.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "flashloan")
public class FlashLoanProperties {

    /** WAL 持久化目录（崩溃恢复的唯一事实来源）。 */
    private String dataDir = "data/flashloan";

    /** 默认手续费率：占借款本金的比例，0.001 表示千分之一。 */
    private BigDecimal defaultFeeRate = new BigDecimal("0.001");

    /** 默认单笔借款本金上限。 */
    private BigDecimal defaultMaxLoanAmount = new BigDecimal("1000000");

    /** 默认单个借款单元允许的资金操作步数上限。 */
    private int defaultMaxSteps = 50;

    /** 单个借款单元执行超时（毫秒），超时按失败回滚处理。 */
    private long executionTimeoutMs = 5000;

    /** 测试辅助：每个资金操作执行前注入的固定延时（毫秒），生产环境保持 0。 */
    private long testStepDelayMs = 0;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public BigDecimal getDefaultFeeRate() {
        return defaultFeeRate;
    }

    public void setDefaultFeeRate(BigDecimal defaultFeeRate) {
        this.defaultFeeRate = defaultFeeRate;
    }

    public BigDecimal getDefaultMaxLoanAmount() {
        return defaultMaxLoanAmount;
    }

    public void setDefaultMaxLoanAmount(BigDecimal defaultMaxLoanAmount) {
        this.defaultMaxLoanAmount = defaultMaxLoanAmount;
    }

    public int getDefaultMaxSteps() {
        return defaultMaxSteps;
    }

    public void setDefaultMaxSteps(int defaultMaxSteps) {
        this.defaultMaxSteps = defaultMaxSteps;
    }

    public long getExecutionTimeoutMs() {
        return executionTimeoutMs;
    }

    public void setExecutionTimeoutMs(long executionTimeoutMs) {
        this.executionTimeoutMs = executionTimeoutMs;
    }

    public long getTestStepDelayMs() {
        return testStepDelayMs;
    }

    public void setTestStepDelayMs(long testStepDelayMs) {
        this.testStepDelayMs = testStepDelayMs;
    }
}

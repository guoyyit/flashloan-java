package com.github.guoyyit.flashloanjava.service;

import java.math.BigDecimal;

import com.github.guoyyit.flashloanjava.domain.model.FailureReason;
import com.github.guoyyit.flashloanjava.domain.model.LoanConfig;
import com.github.guoyyit.flashloanjava.storage.DurableStore;
import com.github.guoyyit.flashloanjava.storage.PersistentState;
import org.springframework.stereotype.Service;

@Service
public class ConfigService {

    private final DurableStore store;

    public ConfigService(DurableStore store) {
        this.store = store;
    }

    public LoanConfig currentConfig() {
        return store.readCommitted().getConfig();
    }

    /**
     * 原子更新配置并持久化，版本号单调递增。空参数字段表示“不修改该项”。
     * 更新与借款写事务串行：进行中的借款单元持有的是开始执行时的配置快照，
     * 因此更新只对“更新提交之后开始”的借款单元生效，生效时点确定。
     */
    public LoanConfig update(BigDecimal feeRate, BigDecimal maxLoanAmount, Integer maxSteps) {
        validate(feeRate, maxLoanAmount, maxSteps);
        PersistentState state = store.beginTransaction();
        try {
            LoanConfig current = state.getConfig();
            LoanConfig next = new LoanConfig(
                    current.getVersion() + 1,
                    feeRate != null ? feeRate : current.getFeeRate(),
                    maxLoanAmount != null ? maxLoanAmount : current.getMaxLoanAmount(),
                    maxSteps != null ? maxSteps : current.getMaxSteps());
            state.setConfig(next);
            store.commitTransaction();
            return next;
        } catch (RuntimeException e) {
            store.rollbackTransaction();
            throw e;
        }
    }

    private void validate(BigDecimal feeRate, BigDecimal maxLoanAmount, Integer maxSteps) {
        if (feeRate != null
                && (feeRate.signum() < 0 || feeRate.compareTo(BigDecimal.ONE) > 0)) {
            throw new FlashLoanException(FailureReason.INVALID_REQUEST,
                    "手续费率必须位于 [0, 1] 区间内");
        }
        if (maxLoanAmount != null && maxLoanAmount.signum() <= 0) {
            throw new FlashLoanException(FailureReason.INVALID_REQUEST,
                    "单笔借款上限必须为正数");
        }
        if (maxSteps != null && maxSteps <= 0) {
            throw new FlashLoanException(FailureReason.INVALID_REQUEST,
                    "执行步数上限必须为正整数");
        }
    }
}

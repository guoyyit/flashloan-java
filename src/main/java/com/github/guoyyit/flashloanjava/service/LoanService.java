package com.github.guoyyit.flashloanjava.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.github.guoyyit.flashloanjava.config.FlashLoanProperties;
import com.github.guoyyit.flashloanjava.domain.model.FailureReason;
import com.github.guoyyit.flashloanjava.domain.model.LoanConfig;
import com.github.guoyyit.flashloanjava.domain.model.LoanRecord;
import com.github.guoyyit.flashloanjava.domain.model.LoanStatus;
import com.github.guoyyit.flashloanjava.domain.model.OperationType;
import com.github.guoyyit.flashloanjava.domain.model.Pool;
import com.github.guoyyit.flashloanjava.storage.DurableStore;
import com.github.guoyyit.flashloanjava.storage.PersistentState;
import com.github.guoyyit.flashloanjava.web.dto.LoanRequest;
import com.github.guoyyit.flashloanjava.web.dto.OperationSpec;
import org.springframework.stereotype.Service;

@Service
public class LoanService {

    private final DurableStore store;
    private final OperationEngine engine;
    private final FlashLoanProperties properties;

    public LoanService(DurableStore store, OperationEngine engine, FlashLoanProperties properties) {
        this.store = store;
        this.engine = engine;
        this.properties = properties;
    }

    /**
     * 原子执行一个借款单元。
     *
     * <p>所有校验、放款、用户操作与归还都发生在已提交状态的深拷贝上；
     * 只有全部成功并通过 fsync 快照落盘后才对外可见。任一环节抛出异常，
     * 工作副本被整体丢弃，资金池、手续费与记录均无任何变化。
     */
    public LoanRecord executeLoan(LoanRequest request) {
        validateRequest(request);
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(properties.getExecutionTimeoutMs());

        PersistentState state = store.beginTransaction();
        try {
            LoanConfig config = state.getConfig();
            if (request.getOperations().size() > config.getMaxSteps()) {
                throw new FlashLoanException(FailureReason.STEP_LIMIT_EXCEEDED,
                        "操作步数 " + request.getOperations().size()
                                + " 超过上限 " + config.getMaxSteps());
            }
            if (request.getAmount().compareTo(config.getMaxLoanAmount()) > 0) {
                throw new FlashLoanException(FailureReason.LOAN_LIMIT_EXCEEDED,
                        "借款金额 " + request.getAmount()
                                + " 超过单笔上限 " + config.getMaxLoanAmount());
            }
            Pool pool = state.getPools().get(request.getPoolId());
            if (pool == null) {
                throw new FlashLoanException(FailureReason.POOL_NOT_FOUND,
                        "资金池不存在: " + request.getPoolId());
            }
            if (!pool.getBalances().containsKey(request.getCurrency())) {
                throw new FlashLoanException(FailureReason.CURRENCY_NOT_FOUND,
                        "资金池 " + request.getPoolId() + " 不持有币种 "
                                + request.getCurrency());
            }
            BigDecimal available = pool.getBalances().getOrDefault(
                    request.getCurrency(), BigDecimal.ZERO);
            if (available.compareTo(request.getAmount()) < 0) {
                throw new FlashLoanException(FailureReason.INSUFFICIENT_POOL_BALANCE,
                        "资金池可用余额不足: 需要 " + request.getAmount()
                                + "，可用 " + available);
            }

            BigDecimal fee = request.getAmount().multiply(config.getFeeRate());
            BigDecimal due = request.getAmount().add(fee);

            OperationEngine.Execution execution = engine.execute(
                    state,
                    request.getPoolId(),
                    request.getCurrency(),
                    request.getAmount(),
                    request.getOperations(),
                    deadline,
                    properties.getTestStepDelayMs());

            settleRepayment(state, request, execution, due, fee);

            LoanRecord record = new LoanRecord();
            record.setId(state.getNextLoanId());
            state.setNextLoanId(state.getNextLoanId() + 1);
            record.setStatus(LoanStatus.COMMITTED);
            record.setPoolId(request.getPoolId());
            record.setCurrency(request.getCurrency());
            record.setAmount(request.getAmount());
            record.setFeeRate(config.getFeeRate());
            record.setFee(fee);
            record.setConfigVersion(config.getVersion());
            record.setExecutedSteps(execution.steps().size());
            record.setSteps(execution.steps());
            state.getRecords().add(record);

            store.commitTransaction();
            return record;
        } catch (RuntimeException e) {
            store.rollbackTransaction();
            throw e;
        }
    }

    /** 归还结算：本金与手续费必须足额回到借款池，借款方不得留有任何币种余额。 */
    private void settleRepayment(PersistentState state, LoanRequest request,
                                 OperationEngine.Execution execution,
                                 BigDecimal due, BigDecimal fee) {
        Pool loanPool = state.getPools().get(request.getPoolId());
        Map<String, BigDecimal> balances = execution.borrowerBalances();

        BigDecimal returned = balances.getOrDefault(request.getCurrency(), BigDecimal.ZERO);
        int compare = returned.compareTo(due);
        if (compare < 0) {
            throw new FlashLoanException(FailureReason.INSUFFICIENT_REPAYMENT,
                    "归还不足: 应还本金+手续费 " + due + "（本金 "
                            + request.getAmount() + "，手续费 " + fee
                            + "），实际回到借款池 " + returned);
        }
        if (compare > 0) {
            throw new FlashLoanException(FailureReason.REPAYMENT_SURPLUS,
                    "归还超额: 应还 " + due + "，实际 " + returned
                            + "，资金不允许滞留给借款方，原子单元整体回滚");
        }

        for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
            String currency = entry.getKey();
            BigDecimal held = entry.getValue();
            if (!currency.equals(request.getCurrency()) && held.signum() != 0) {
                throw new FlashLoanException(FailureReason.INSUFFICIENT_REPAYMENT,
                        "执行结束后借款方仍持有未归还的币种 " + currency
                                + "，余额 " + held + "，原子单元整体回滚");
            }
        }
        // 足额归还：本金与手续费一并贷记回借款池，手续费沉淀在池中。
        loanPool.getBalances().merge(request.getCurrency(), returned, BigDecimal::add);
        balances.put(request.getCurrency(), BigDecimal.ZERO);
        execution.steps().add(repaymentStep(execution.steps().size(),
                request.getPoolId(), request.getCurrency(), returned, fee));
    }

    private LoanRecord.Step repaymentStep(int index, String poolId, String currency,
                                          BigDecimal total, BigDecimal fee) {
        LoanRecord.Step step = new LoanRecord.Step();
        step.setIndex(index);
        step.setType(OperationType.TRANSFER);
        step.setFromPool(OperationEngine.BORROWER);
        step.setToPool(poolId);
        step.setCurrency(currency);
        step.setAmount(total);
        step.setFee(fee);
        return step;
    }

    public List<LoanRecord> listRecords() {
        return new ArrayList<>(store.readCommitted().getRecords());
    }

    public LoanRecord getRecord(long id) {
        LoanRecord record = null;
        for (LoanRecord candidate : store.readCommitted().getRecords()) {
            if (candidate.getId() == id) {
                record = candidate;
                break;
            }
        }
        if (record == null) {
            throw new FlashLoanException(FailureReason.INVALID_REQUEST,
                    "借款记录不存在（失败单元不会产生记录与手续费）: " + id);
        }
        return record;
    }

    private void validateRequest(LoanRequest request) {
        if (request == null) {
            throw new FlashLoanException(FailureReason.INVALID_REQUEST, "请求体不能为空");
        }
        if (request.getPoolId() == null || request.getPoolId().isBlank()) {
            throw new FlashLoanException(FailureReason.INVALID_REQUEST, "poolId 不能为空");
        }
        if (request.getCurrency() == null || request.getCurrency().isBlank()) {
            throw new FlashLoanException(FailureReason.INVALID_REQUEST, "currency 不能为空");
        }
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new FlashLoanException(FailureReason.INVALID_AMOUNT,
                    "借款金额必须为正数");
        }
        if (request.getOperations() == null) {
            request.setOperations(List.of());
        }
        for (OperationSpec spec : request.getOperations()) {
            if (spec == null) {
                throw new FlashLoanException(FailureReason.INVALID_OPERATION,
                        "操作列表不能包含 null 元素");
            }
        }
    }
}

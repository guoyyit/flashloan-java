package com.github.guoyyit.flashloanjava.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.github.guoyyit.flashloanjava.domain.model.FailureReason;
import com.github.guoyyit.flashloanjava.domain.model.LoanRecord;
import com.github.guoyyit.flashloanjava.domain.model.OperationType;
import com.github.guoyyit.flashloanjava.domain.model.Pool;
import com.github.guoyyit.flashloanjava.storage.PersistentState;
import com.github.guoyyit.flashloanjava.web.dto.OperationSpec;
import org.springframework.stereotype.Component;

@Component
public class OperationEngine {

    /** 借款方在原子单元执行期间的虚拟账户标识，不参与持久化。 */
    public static final String BORROWER = "<borrower>";

    /**
     * 在工作副本上执行全部操作。
     *
     * @param state     事务工作副本（含资金池快照）
     * @param loanPool  借出资金的池 ID
     * @param currency  借出币种
     * @param principal 借出本金（放款前已从池中扣除到虚拟账户）
     * @param specs     用户声明的有序资金操作
     * @param deadlineNanos 执行截止时刻（System.nanoTime），超过则超时失败
     * @param stepDelayMs 每个操作前注入的延时（仅用于超时测试，正常为 0）
     */
    public Execution execute(PersistentState state, String loanPool, String currency,
                             BigDecimal principal, List<OperationSpec> specs,
                             long deadlineNanos, long stepDelayMs) {
        Map<String, Map<String, BigDecimal>> virtual = new HashMap<>();
        virtual.put(BORROWER, new HashMap<>());
        List<LoanRecord.Step> steps = new ArrayList<>();

        // 放款：从借款池扣除本金到借款方虚拟账户。
        debit(state, loanPool, currency, principal);
        credit(virtual, BORROWER, currency, principal);

        for (int i = 0; i < specs.size(); i++) {
            if (stepDelayMs > 0) {
                try {
                    Thread.sleep(stepDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new FlashLoanException(FailureReason.EXECUTION_TIMEOUT,
                            "借款单元执行被中断");
                }
            }
            if (System.nanoTime() > deadlineNanos) {
                throw new FlashLoanException(FailureReason.EXECUTION_TIMEOUT,
                        "借款单元执行超过时限，整体回滚");
            }
            OperationSpec spec = specs.get(i);
            LoanRecord.Step step = new LoanRecord.Step();
            step.setIndex(i);
            if (spec.getType() == null) {
                throw new FlashLoanException(FailureReason.INVALID_OPERATION,
                        "第 " + i + " 步缺少操作类型");
            }
            switch (spec.getType()) {
                case TRANSFER -> applyTransfer(state, virtual, spec, step);
                case SWAP -> applySwap(state, virtual, spec, step);
                default -> throw new FlashLoanException(FailureReason.INVALID_OPERATION,
                        "不支持的操作类型: " + spec.getType());
            }
            steps.add(step);
        }
        return new Execution(virtual, steps);
    }

    private void applyTransfer(PersistentState state, Map<String, Map<String, BigDecimal>> virtual,
                               OperationSpec spec, LoanRecord.Step step) {
        String from = requireText(spec.getFromPool(), "TRANSFER 缺少 fromPool");
        String to = requireText(spec.getToPool(), "TRANSFER 缺少 toPool");
        String currency = requireText(spec.getCurrency(), "TRANSFER 缺少 currency");
        BigDecimal amount = requirePositive(spec.getAmount(), "TRANSFER 金额必须为正数");
        if (from.equals(to)) {
            throw new FlashLoanException(FailureReason.INVALID_OPERATION,
                    "TRANSFER 的来源与目标不能相同");
        }
        move(state, virtual, from, to, currency, amount);
        step.setType(OperationType.TRANSFER);
        step.setFromPool(maskVirtual(from));
        step.setToPool(maskVirtual(to));
        step.setCurrency(currency);
        step.setAmount(amount);
    }

    private void applySwap(PersistentState state, Map<String, Map<String, BigDecimal>> virtual,
                           OperationSpec spec, LoanRecord.Step step) {
        String poolId = requireText(spec.getPool(), "SWAP 缺少 pool(兑换池)");
        String initiator = spec.getFromPool() == null || spec.getFromPool().isBlank()
                ? BORROWER : spec.getFromPool().trim();
        String fromCurrency = requireText(spec.getFromCurrency(), "SWAP 缺少 fromCurrency");
        String toCurrency = requireText(spec.getToCurrency(), "SWAP 缺少 toCurrency");
        BigDecimal fromAmount = requirePositive(spec.getFromAmount(), "SWAP 的 fromAmount 必须为正数");
        BigDecimal toAmount = requirePositive(spec.getToAmount(), "SWAP 的 toAmount 必须为正数");
        if (fromCurrency.equals(toCurrency)) {
            throw new FlashLoanException(FailureReason.INVALID_OPERATION,
                    "SWAP 的两种币种必须不同");
        }
        Pool pool = state.getPools().get(poolId);
        if (pool == null) {
            throw new FlashLoanException(FailureReason.POOL_NOT_FOUND, "兑换池不存在: " + poolId);
        }
        if (BORROWER.equals(initiator)) {
            // 借款方与池兑换：向池付出 fromCurrency，从池取得 toCurrency。
            debitVirtual(virtual, BORROWER, fromCurrency, fromAmount);
            pool.getBalances().merge(fromCurrency, fromAmount, BigDecimal::add);
            debitPool(pool, toCurrency, toAmount);
            credit(virtual, BORROWER, toCurrency, toAmount);
        } else if (initiator.equals(poolId)) {
            // 池内自兑：池消耗自有 fromCurrency，买入 toCurrency。
            debitPool(pool, fromCurrency, fromAmount);
            pool.getBalances().merge(toCurrency, toAmount, BigDecimal::add);
        } else {
            throw new FlashLoanException(FailureReason.INVALID_OPERATION,
                    "SWAP 的 fromPool 只能是 <borrower> 或兑换池自身");
        }
        step.setType(OperationType.SWAP);
        step.setFromPool(initiator);
        step.setToPool(poolId);
        step.setFromCurrency(fromCurrency);
        step.setToCurrency(toCurrency);
        step.setFromAmount(fromAmount);
        step.setToAmount(toAmount);
    }

    /** 在资金池与虚拟账户之间移动同币种资金。 */
    private void move(PersistentState state, Map<String, Map<String, BigDecimal>> virtual,
                      String from, String to, String currency, BigDecimal amount) {
        if (BORROWER.equals(from)) {
            debitVirtual(virtual, BORROWER, currency, amount);
            creditPool(state, to, currency, amount);
        } else if (BORROWER.equals(to)) {
            debit(state, from, currency, amount);
            credit(virtual, BORROWER, currency, amount);
        } else {
            debit(state, from, currency, amount);
            creditPool(state, to, currency, amount);
        }
    }

    private void debit(PersistentState state, String poolId, String currency, BigDecimal amount) {
        Pool pool = state.getPools().get(poolId);
        if (pool == null) {
            throw new FlashLoanException(FailureReason.POOL_NOT_FOUND, "资金池不存在: " + poolId);
        }
        debitPool(pool, currency, amount);
    }

    private void debitPool(Pool pool, String currency, BigDecimal amount) {
        BigDecimal current = pool.getBalances().getOrDefault(currency, BigDecimal.ZERO);
        if (current.compareTo(amount) < 0) {
            throw new FlashLoanException(FailureReason.INSUFFICIENT_POOL_BALANCE,
                    "资金池 " + pool.getId() + " 的币种 " + currency
                            + " 余额不足: 需要 " + amount + "，可用 " + current);
        }
        pool.getBalances().put(currency, current.subtract(amount));
    }

    private void creditPool(PersistentState state, String poolId, String currency,
                            BigDecimal amount) {
        Pool pool = state.getPools().get(poolId);
        if (pool == null) {
            throw new FlashLoanException(FailureReason.POOL_NOT_FOUND, "资金池不存在: " + poolId);
        }
        pool.getBalances().merge(currency, amount, BigDecimal::add);
    }

    private void debitVirtual(Map<String, Map<String, BigDecimal>> virtual, String account,
                              String currency, BigDecimal amount) {
        BigDecimal current = virtual.get(account).getOrDefault(currency, BigDecimal.ZERO);
        if (current.compareTo(amount) < 0) {
            throw new FlashLoanException(FailureReason.INVALID_OPERATION,
                    "借款方虚拟账户币种 " + currency + " 余额不足: 需要 " + amount
                            + "，可用 " + current);
        }
        virtual.get(account).put(currency, current.subtract(amount));
    }

    private void credit(Map<String, Map<String, BigDecimal>> virtual, String account,
                        String currency, BigDecimal amount) {
        virtual.computeIfAbsent(account, key -> new HashMap<>())
                .merge(currency, amount, BigDecimal::add);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new FlashLoanException(FailureReason.INVALID_OPERATION, message);
        }
        return value.trim();
    }

    private BigDecimal requirePositive(BigDecimal value, String message) {
        if (value == null || value.signum() <= 0) {
            throw new FlashLoanException(FailureReason.INVALID_AMOUNT, message);
        }
        return value;
    }

    private String maskVirtual(String id) {
        return BORROWER.equals(id) ? BORROWER : id;
    }

    /** 一次执行的结果：终态虚拟账户余额与已成功执行的审计步骤。 */
    public static class Execution {

        private final Map<String, Map<String, BigDecimal>> virtualBalances;
        private final List<LoanRecord.Step> steps;

        public Execution(Map<String, Map<String, BigDecimal>> virtualBalances,
                         List<LoanRecord.Step> steps) {
            this.virtualBalances = virtualBalances;
            this.steps = steps;
        }

        public Map<String, BigDecimal> borrowerBalances() {
            return virtualBalances.get(BORROWER);
        }

        public TreeMap<String, BigDecimal> borrowerBalancesSorted() {
            return new TreeMap<>(virtualBalances.get(BORROWER));
        }

        public List<LoanRecord.Step> steps() {
            return steps;
        }
    }
}

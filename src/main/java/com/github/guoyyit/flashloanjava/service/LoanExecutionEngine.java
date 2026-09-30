package com.github.guoyyit.flashloanjava.service;

import com.github.guoyyit.flashloanjava.domain.Pool;
import com.github.guoyyit.flashloanjava.domain.ServiceConfig;
import com.github.guoyyit.flashloanjava.repository.ConfigRepository;
import com.github.guoyyit.flashloanjava.repository.LedgerRepository;
import com.github.guoyyit.flashloanjava.repository.LoanRepository;
import com.github.guoyyit.flashloanjava.repository.PoolRepository;
import com.github.guoyyit.flashloanjava.web.dto.FundOperationRequest;
import com.github.guoyyit.flashloanjava.web.dto.LoanRequest;
import com.github.guoyyit.flashloanjava.web.dto.LoanResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class LoanExecutionEngine {

    static final String LOAN_ACCOUNT_PREFIX = "loan:";

    private final ConfigRepository configRepository;
    private final PoolRepository poolRepository;
    private final LoanRepository loanRepository;
    private final LedgerRepository ledgerRepository;

    public LoanExecutionEngine(ConfigRepository configRepository,
                               PoolRepository poolRepository,
                               LoanRepository loanRepository,
                               LedgerRepository ledgerRepository) {
        this.configRepository = configRepository;
        this.poolRepository = poolRepository;
        this.loanRepository = loanRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional
    public LoanResponse execute(LoanRequest request) {
        long startNanos = System.nanoTime();
        ServiceConfig config = configRepository.get();

        long amount = requirePositive(request.amount(), "amount");
        long poolId = requireId(request.poolId(), "poolId");
        if (amount > config.maxLoanAmount()) {
            throw fail("LOAN_AMOUNT_EXCEEDED",
                    "amount " + amount + " exceeds per-loan limit " + config.maxLoanAmount());
        }
        List<FundOperationRequest> operations = request.operations() == null
                ? List.of() : request.operations();
        if (operations.size() > config.maxSteps()) {
            throw fail("TOO_MANY_STEPS",
                    "operations " + operations.size() + " exceed max steps " + config.maxSteps());
        }

        long timeoutMs = request.timeoutMs() == null
                ? config.timeoutMs() : Math.min(request.timeoutMs(), config.timeoutMs());
        long deadlineNanos = startNanos + timeoutMs * 1_000_000L;

        List<Long> touchedPoolIds = collectTouchedPools(poolId, operations);
        Map<Long, Pool> lockedPools = lockPools(poolId, touchedPoolIds);
        Pool borrowPool = lockedPools.get(poolId);
        if (borrowPool == null) {
            throw fail("POOL_NOT_FOUND", "pool not found: " + poolId);
        }
        // Liquidity is evaluated only after every touched pool row is locked,
        // using the current row value, so concurrent units can never observe a
        // stale balance or double-spend the same funds.
        if (borrowPool.balance() < amount) {
            throw fail("INSUFFICIENT_LIQUIDITY",
                    "pool " + poolId + " balance " + borrowPool.balance() + " < loan " + amount);
        }

        loanRepository.insertRunning(request.requestId(), poolId, borrowPool.currency(),
                amount, config.version());

        Map<Long, Long> balances = new HashMap<>();
        for (Pool pool : lockedPools.values()) {
            balances.put(pool.id(), pool.balance());
        }
        Map<String, Long> virtual = new HashMap<>();

        long borrowAfterDisburse = borrowPool.balance() - amount;
        balances.put(poolId, borrowAfterDisburse);
        poolRepository.updateBalance(poolId, borrowAfterDisburse);
        ledgerRepository.append(request.requestId(), -1, "pool:" + poolId, borrowPool.currency(),
                -amount, borrowAfterDisburse, "DISBURSE");
        virtual.put(borrowPool.currency(), amount);
        ledgerRepository.append(request.requestId(), -1, LOAN_ACCOUNT_PREFIX + borrowPool.currency(),
                borrowPool.currency(), amount, amount, "DISBURSE");

        for (int step = 0; step < operations.size(); step++) {
            if (System.nanoTime() > deadlineNanos) {
                throw fail("EXECUTION_TIMEOUT",
                        "loan unit exceeded timeout of " + timeoutMs + " ms at step " + step);
            }
            FundOperationRequest op = operations.get(step);
            if (op.delayMs() != null && op.delayMs() > 0) {
                try {
                    Thread.sleep(Math.min(op.delayMs(), timeoutMs + 1L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw fail("EXECUTION_INTERRUPTED", "unit interrupted at step " + step);
                }
            }
            applyOperation(request.requestId(), step, op, balances, virtual, lockedPools);
        }
        if (Boolean.TRUE.equals(request.crashBeforeCommit())) {
            crashImmediately();
        }

        long fee = BigDecimal.valueOf(amount)
                .multiply(config.feeRate())
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
        // The borrow pool must end at least at its opening balance plus the
        // fee (principal is fully restored and the fee is retained as profit).
        long requiredFinalBalance = borrowPool.balance() + fee;
        long finalBorrowBalance = balances.get(poolId);
        long amountReturned = finalBorrowBalance - borrowAfterDisburse;
        if (finalBorrowBalance < requiredFinalBalance) {
            throw fail("REPAYMENT_SHORTFALL",
                    "borrow pool final balance " + finalBorrowBalance
                            + " < opening balance + fee " + requiredFinalBalance);
        }
        for (Map.Entry<String, Long> held : virtual.entrySet()) {
            if (held.getValue() != 0) {
                throw fail("FUNDS_NOT_RETURNED",
                        "borrower still holds " + held.getValue() + " " + held.getKey()
                                + " at end of unit");
            }
        }

        // Repayment into the borrow pool is delivered by the user operations
        // themselves; here we only assert the invariants and mark the unit
        // committed. Writing an extra REPAY entry would double-count funds.
        loanRepository.markSuccess(request.requestId(), fee, amountReturned);
        if (Boolean.TRUE.equals(request.crashAfterCommit())) {
            crashAfterCommit();
        }

        return new LoanResponse(request.requestId(), "SUCCESS", borrowPool.currency(),
                amount, fee, amountReturned, config.version(), null);
    }

    private void applyOperation(String requestId, int step, FundOperationRequest op,
                                Map<Long, Long> balances, Map<String, Long> virtual,
                                Map<Long, Pool> pools) {
        if (op == null || op.type() == null) {
            throw fail("INVALID_OPERATION", "operation type is required at step " + step);
        }
        String type = op.type().trim().toUpperCase();
        switch (type) {
            case "TRANSFER" -> applyTransfer(requestId, step, op, balances, virtual, pools);
            case "EXCHANGE" -> applyExchange(requestId, step, op, balances, virtual, pools);
            default -> throw fail("INVALID_OPERATION",
                    "unknown operation type '" + op.type() + "' at step " + step);
        }
    }

    private void applyTransfer(String requestId, int step, FundOperationRequest op,
                               Map<Long, Long> balances, Map<String, Long> virtual,
                               Map<Long, Pool> pools) {
        long amount = requirePositive(op.amount(), "amount at step " + step);
        Long fromPoolId = op.fromPoolId();
        Long toPoolId = op.toPoolId();
        if ((fromPoolId == null) == (toPoolId == null)) {
            throw fail("INVALID_OPERATION",
                    "transfer must move between the loan account and exactly one pool at step " + step);
        }
        String currency = resolveTransferCurrency(op, pools, fromPoolId == null, step);
        if (fromPoolId == null) {
            Pool target = pools.get(requireId(toPoolId, "toPoolId at step " + step));
            requireCurrency(target, currency, step);
            debitVirtual(requestId, step, currency, amount, virtual, "TRANSFER_OUT");
            creditPool(requestId, step, target, currency, amount, balances, "TRANSFER_IN");
        } else {
            Pool source = pools.get(requireId(fromPoolId, "fromPoolId at step " + step));
            requireCurrency(source, currency, step);
            debitPool(requestId, step, source, currency, amount, balances, "TRANSFER_OUT");
            creditVirtual(requestId, step, currency, amount, virtual, "TRANSFER_IN");
        }
    }

    private void applyExchange(String requestId, int step, FundOperationRequest op,
                               Map<Long, Long> balances, Map<String, Long> virtual,
                               Map<Long, Pool> pools) {
        long amount = requirePositive(op.amount(), "amount at step " + step);
        if (op.rate() == null || op.rate().signum() <= 0) {
            throw fail("INVALID_OPERATION", "exchange rate must be positive at step " + step);
        }
        BigDecimal convertedDecimal = op.rate().multiply(BigDecimal.valueOf(amount))
                .setScale(0, RoundingMode.DOWN);
        if (convertedDecimal.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            throw fail("AMOUNT_OVERFLOW", "converted amount overflows at step " + step);
        }
        long converted = convertedDecimal.longValue();
        if (converted <= 0) {
            throw fail("INVALID_OPERATION",
                    "exchange converted amount must be positive at step " + step);
        }
        Long fromPoolId = op.fromPoolId();
        Long toPoolId = op.toPoolId();
        if (fromPoolId != null && toPoolId != null && fromPoolId.longValue() == toPoolId.longValue()) {
            throw fail("INVALID_OPERATION",
                    "exchange from/to pools must differ at step " + step);
        }
        String fromCurrency = resolveExchangeSideCurrency(op.currency(),
                fromPoolId == null ? null : pools.get(fromPoolId), "currency", step);
        String toCurrency = resolveExchangeSideCurrency(op.toCurrency(),
                toPoolId == null ? null : pools.get(toPoolId), "toCurrency", step);
        if (fromCurrency.equals(toCurrency)) {
            throw fail("INVALID_OPERATION", "exchange currencies must differ at step " + step);
        }
        debitExchangeSide(requestId, step, fromPoolId, fromCurrency, amount,
                balances, virtual, pools, "EXCHANGE_OUT");
        creditExchangeSide(requestId, step, toPoolId, toCurrency, converted,
                balances, virtual, pools, "EXCHANGE_IN");
    }

    private void debitExchangeSide(String requestId, int step, Long poolId, String currency,
                                   long amount, Map<Long, Long> balances, Map<String, Long> virtual,
                                   Map<Long, Pool> pools, String action) {
        if (poolId == null) {
            debitVirtual(requestId, step, currency, amount, virtual, action);
        } else {
            Pool pool = pools.get(requireId(poolId, "fromPoolId at step " + step));
            requireCurrency(pool, currency, step);
            debitPool(requestId, step, pool, currency, amount, balances, action);
        }
    }

    private void creditExchangeSide(String requestId, int step, Long poolId, String currency,
                                    long amount, Map<Long, Long> balances, Map<String, Long> virtual,
                                    Map<Long, Pool> pools, String action) {
        if (poolId == null) {
            creditVirtual(requestId, step, currency, amount, virtual, action);
        } else {
            Pool pool = pools.get(requireId(poolId, "toPoolId at step " + step));
            requireCurrency(pool, currency, step);
            creditPool(requestId, step, pool, currency, amount, balances, action);
        }
    }

    private void debitPool(String requestId, int step, Pool pool, long amount,
                           Map<Long, Long> balances) {
        debitPool(requestId, step, pool, pool.currency(), amount, balances, "TRANSFER_OUT");
    }

    private void debitPool(String requestId, int step, Pool pool, String currency, long amount,
                           Map<Long, Long> balances, String action) {
        long current = balances.get(pool.id());
        if (current < amount) {
            throw fail("INSUFFICIENT_FUNDS",
                    "pool " + pool.id() + " balance " + current + " < required " + amount
                            + " at step " + step);
        }
        long next = current - amount;
        balances.put(pool.id(), next);
        poolRepository.updateBalance(pool.id(), next);
        ledgerRepository.append(requestId, step, "pool:" + pool.id(), currency,
                -amount, next, action);
    }

    private void creditPool(String requestId, int step, Pool pool, String currency, long amount,
                            Map<Long, Long> balances, String action) {
        long next;
        try {
            next = Math.addExact(balances.get(pool.id()), amount);
        } catch (ArithmeticException e) {
            throw fail("AMOUNT_OVERFLOW", "pool balance overflow at step " + step);
        }
        balances.put(pool.id(), next);
        poolRepository.updateBalance(pool.id(), next);
        ledgerRepository.append(requestId, step, "pool:" + pool.id(), currency,
                amount, next, action);
    }

    private void debitVirtual(String requestId, int step, String currency, long amount,
                              Map<String, Long> virtual, String action) {
        long current = virtual.getOrDefault(currency, 0L);
        if (current < amount) {
            throw fail("INSUFFICIENT_FUNDS",
                    "borrower holds " + current + " " + currency + " < required " + amount
                            + " at step " + step);
        }
        long next = current - amount;
        virtual.put(currency, next);
        ledgerRepository.append(requestId, step, LOAN_ACCOUNT_PREFIX + currency, currency,
                -amount, next, action);
    }

    private void creditVirtual(String requestId, int step, String currency, long amount,
                               Map<String, Long> virtual, String action) {
        long next;
        try {
            next = Math.addExact(virtual.getOrDefault(currency, 0L), amount);
        } catch (ArithmeticException e) {
            throw fail("AMOUNT_OVERFLOW", "virtual balance overflow for " + currency
                    + " at step " + step);
        }
        virtual.put(currency, next);
        ledgerRepository.append(requestId, step, LOAN_ACCOUNT_PREFIX + currency, currency,
                amount, next, action);
    }

    private List<Long> collectTouchedPools(long borrowPoolId, List<FundOperationRequest> operations) {
        java.util.TreeSet<Long> ids = new java.util.TreeSet<>();
        for (FundOperationRequest op : operations) {
            if (op == null) {
                continue;
            }
            if (op.fromPoolId() != null) {
                ids.add(op.fromPoolId());
            }
            if (op.toPoolId() != null) {
                ids.add(op.toPoolId());
            }
        }
        ids.remove(borrowPoolId);
        return List.copyOf(ids);
    }

    private Map<Long, Pool> lockPools(long borrowPoolId, List<Long> touchedPoolIds) {
        java.util.TreeSet<Long> ordered = new java.util.TreeSet<>();
        ordered.add(borrowPoolId);
        ordered.addAll(touchedPoolIds);
        List<Pool> lockedList = poolRepository.findAllByIdInOrderForUpdate(List.copyOf(ordered));
        if (lockedList.size() != ordered.size()) {
            List<Long> found = lockedList.stream().map(Pool::id).toList();
            List<Long> missing = ordered.stream().filter(id -> !found.contains(id)).toList();
            throw fail("POOL_NOT_FOUND", "pool(s) not found: " + missing);
        }
        Map<Long, Pool> locked = new HashMap<>();
        for (Pool pool : lockedList) {
            locked.put(pool.id(), pool);
        }
        return locked;
    }

    private String resolveTransferCurrency(FundOperationRequest op, Map<Long, Pool> pools,
                                           boolean fromLoan, int step) {
        if (fromLoan) {
            return resolveExchangeSideCurrency(op.currency(),
                    pools.get(op.toPoolId()), "currency", step);
        }
        return resolveExchangeSideCurrency(op.currency(),
                pools.get(op.fromPoolId()), "currency", step);
    }

    // A side currency may be declared explicitly or inferred from the pool on
    // that side. An explicit declaration that disagrees with the pool rejects
    // the operation deterministically.
    private String resolveExchangeSideCurrency(String declared, Pool sidePool, String field, int step) {
        if (declared != null && !declared.isBlank()) {
            String currency = declared.trim().toUpperCase();
            if (sidePool != null && !sidePool.currency().equals(currency)) {
                throw fail("CURRENCY_MISMATCH",
                        field + " " + currency + " does not match pool " + sidePool.id()
                                + " currency " + sidePool.currency() + " at step " + step);
            }
            return currency;
        }
        if (sidePool == null) {
            throw fail("INVALID_OPERATION", field + " is required at step " + step);
        }
        return sidePool.currency();
    }

    private void requireCurrency(Pool pool, String currency, int step) {
        if (pool == null) {
            throw fail("POOL_NOT_FOUND", "pool referenced at step " + step + " is not locked");
        }
        if (!pool.currency().equals(currency)) {
            throw fail("CURRENCY_MISMATCH",
                    "pool " + pool.id() + " holds " + pool.currency()
                            + " but operation uses " + currency + " at step " + step);
        }
    }

    private long requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw fail("INVALID_REQUEST", field + " must be positive");
        }
        return value;
    }

    private long requireId(Long value, String field) {
        if (value == null || value <= 0) {
            throw fail("INVALID_REQUEST", field + " is required");
        }
        return value;
    }

    private FlashLoanException fail(String code, String message) {
        return new FlashLoanException(code, message, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // Test-only crash hook. Enabled explicitly via a system property so a normal
    // deployment can never be terminated by a request field.
    void crashAfterCommit() {
        if (!Boolean.getBoolean("flashloan.crash-hook-enabled")) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Runtime.getRuntime().halt(137);
            }
        });
    }

    // Simulates a hard JVM crash with the loan transaction still open.
    void crashImmediately() {
        if (!Boolean.getBoolean("flashloan.crash-hook-enabled")) {
            return;
        }
        Runtime.getRuntime().halt(137);
    }
}

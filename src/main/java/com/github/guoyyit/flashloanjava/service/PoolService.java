package com.github.guoyyit.flashloanjava.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import com.github.guoyyit.flashloanjava.domain.model.FailureReason;
import com.github.guoyyit.flashloanjava.domain.model.Pool;
import com.github.guoyyit.flashloanjava.storage.DurableStore;
import com.github.guoyyit.flashloanjava.storage.PersistentState;
import org.springframework.stereotype.Service;

@Service
public class PoolService {

    private final DurableStore store;

    public PoolService(DurableStore store) {
        this.store = store;
    }

    /** 创建资金池并按给定的 {币种: 金额} 完成初始注资。 */
    public Pool createPool(String id, String name, TreeMap<String, BigDecimal> initialBalances) {
        if (id == null || id.isBlank()) {
            throw new FlashLoanException(FailureReason.INVALID_REQUEST, "资金池 ID 不能为空");
        }
        validateBalances(initialBalances);
        PersistentState state = store.beginTransaction();
        try {
            if (state.getPools().containsKey(id)) {
                throw new FlashLoanException(FailureReason.INVALID_REQUEST,
                        "资金池已存在: " + id);
            }
            Pool pool = new Pool(id, name == null || name.isBlank() ? id : name);
            TreeMap<String, BigDecimal> balances = pool.getBalances();
            if (initialBalances != null) {
                initialBalances.forEach((currency, amount) ->
                        balances.merge(currency, amount, BigDecimal::add));
            }
            state.getPools().put(id, pool);
            store.commitTransaction();
            return pool;
        } catch (RuntimeException e) {
            store.rollbackTransaction();
            throw e;
        }
    }

    /** 向既有资金池追加流动性。 */
    public Pool addFunds(String poolId, TreeMap<String, BigDecimal> deposits) {
        validateBalances(deposits);
        if (deposits == null || deposits.isEmpty()) {
            throw new FlashLoanException(FailureReason.INVALID_REQUEST, "注资金额不能为空");
        }
        PersistentState state = store.beginTransaction();
        try {
            Pool pool = requirePool(state, poolId);
            deposits.forEach((currency, amount) ->
                    pool.getBalances().merge(currency, amount, BigDecimal::add));
            store.commitTransaction();
            return pool;
        } catch (RuntimeException e) {
            store.rollbackTransaction();
            throw e;
        }
    }

    public List<Pool> listPools() {
        return new ArrayList<>(store.readCommitted().getPools().values());
    }

    public Pool getPool(String poolId) {
        PersistentState state = store.readCommitted();
        return requirePool(state, poolId);
    }

    private Pool requirePool(PersistentState state, String poolId) {
        Pool pool = state.getPools().get(poolId);
        if (pool == null) {
            throw new FlashLoanException(FailureReason.POOL_NOT_FOUND,
                    "资金池不存在: " + poolId);
        }
        return pool;
    }

    private void validateBalances(TreeMap<String, BigDecimal> balances) {
        if (balances == null) {
            return;
        }
        balances.forEach((currency, amount) -> {
            if (currency == null || currency.isBlank()) {
                throw new FlashLoanException(FailureReason.INVALID_REQUEST, "币种代码不能为空");
            }
            if (amount == null || amount.signum() < 0) {
                throw new FlashLoanException(FailureReason.INVALID_AMOUNT,
                        "金额不能为负: " + currency);
            }
        });
    }
}

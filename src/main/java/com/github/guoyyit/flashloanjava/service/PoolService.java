package com.github.guoyyit.flashloanjava.service;

import com.github.guoyyit.flashloanjava.domain.Pool;
import com.github.guoyyit.flashloanjava.repository.LedgerRepository;
import com.github.guoyyit.flashloanjava.repository.PoolRepository;
import com.github.guoyyit.flashloanjava.web.dto.CreatePoolRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PoolService {

    private final PoolRepository poolRepository;
    private final LedgerRepository ledgerRepository;

    public PoolService(PoolRepository poolRepository, LedgerRepository ledgerRepository) {
        this.poolRepository = poolRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @Transactional
    public Pool create(CreatePoolRequest request) {
        if (request == null || request.currency() == null || request.currency().isBlank()) {
            throw new FlashLoanException("currency is required", HttpStatus.BAD_REQUEST);
        }
        long initialDeposit = request.initialDeposit() == null ? 0 : request.initialDeposit();
        if (initialDeposit < 0) {
            throw new FlashLoanException("initialDeposit must not be negative", HttpStatus.BAD_REQUEST);
        }
        String currency = request.currency().trim().toUpperCase();
        Pool pool = poolRepository.create(currency, initialDeposit);
        if (initialDeposit > 0) {
            ledgerRepository.append(null, -1, "pool:" + pool.id(), currency,
                    initialDeposit, initialDeposit, "INITIAL_DEPOSIT");
        }
        return pool;
    }

    @Transactional
    public Pool deposit(long poolId, long amount) {
        if (amount <= 0) {
            throw new FlashLoanException("deposit amount must be positive", HttpStatus.BAD_REQUEST);
        }
        Pool pool = poolRepository.findByIdForUpdate(poolId)
                .orElseThrow(() -> new FlashLoanException("pool not found: " + poolId, HttpStatus.NOT_FOUND));
        long newBalance = pool.balance() + amount;
        poolRepository.updateBalance(poolId, newBalance);
        ledgerRepository.append(null, -1, "pool:" + poolId, pool.currency(),
                amount, newBalance, "DEPOSIT");
        return new Pool(pool.id(), pool.currency(), newBalance, pool.version() + 1, pool.createdAt());
    }

    public Pool get(long poolId) {
        return poolRepository.findById(poolId)
                .orElseThrow(() -> new FlashLoanException("pool not found: " + poolId, HttpStatus.NOT_FOUND));
    }

    public List<Pool> list() {
        return poolRepository.findAll();
    }
}

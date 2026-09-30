package com.github.guoyyit.flashloanjava.service;

import com.github.guoyyit.flashloanjava.domain.LedgerEntry;
import com.github.guoyyit.flashloanjava.domain.LoanRecord;
import com.github.guoyyit.flashloanjava.repository.ConfigRepository;
import com.github.guoyyit.flashloanjava.repository.LedgerRepository;
import com.github.guoyyit.flashloanjava.repository.LoanRepository;
import com.github.guoyyit.flashloanjava.repository.PoolRepository;
import com.github.guoyyit.flashloanjava.web.dto.LoanRequest;
import com.github.guoyyit.flashloanjava.web.dto.LoanResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LoanService {

    private final LoanRepository loanRepository;
    private final LedgerRepository ledgerRepository;
    private final PoolRepository poolRepository;
    private final ConfigRepository configRepository;
    private final LoanExecutionEngine executionEngine;

    public LoanService(LoanRepository loanRepository,
                       LedgerRepository ledgerRepository,
                       PoolRepository poolRepository,
                       ConfigRepository configRepository,
                       LoanExecutionEngine executionEngine) {
        this.loanRepository = loanRepository;
        this.ledgerRepository = ledgerRepository;
        this.poolRepository = poolRepository;
        this.configRepository = configRepository;
        this.executionEngine = executionEngine;
    }

    public LoanResponse execute(LoanRequest request) {
        validateShape(request);

        LoanRecord existing = loanRepository.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) {
            return replay(existing);
        }

        long configVersion = currentConfigVersion();
        long poolId = request.poolId();
        String currency = poolRepository.findById(poolId)
                .map(pool -> pool.currency())
                .orElse("UNKNOWN");

        try {
            return executionEngine.execute(request);
        } catch (DuplicateKeyException concurrentDuplicate) {
            LoanRecord winner = loanRepository.findByRequestId(request.requestId())
                    .orElseThrow(() -> concurrentDuplicate);
            return replay(winner);
        } catch (FlashLoanException ex) {
            persistFailure(request, poolId, currency, configVersion, ex);
            throw ex;
        } catch (RuntimeException ex) {
            FlashLoanException wrapped = new FlashLoanException("EXECUTION_ERROR",
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
            persistFailure(request, poolId, currency, configVersion, wrapped);
            throw wrapped;
        }
    }

    private void validateShape(LoanRequest request) {
        if (request == null) {
            throw new FlashLoanException("INVALID_REQUEST", "request body is required",
                    HttpStatus.BAD_REQUEST);
        }
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw new FlashLoanException("INVALID_REQUEST", "requestId is required",
                    HttpStatus.BAD_REQUEST);
        }
        if (request.requestId().length() > 128) {
            throw new FlashLoanException("INVALID_REQUEST", "requestId must be at most 128 chars",
                    HttpStatus.BAD_REQUEST);
        }
        if (request.poolId() == null || request.poolId() <= 0) {
            throw new FlashLoanException("INVALID_REQUEST", "poolId is required",
                    HttpStatus.BAD_REQUEST);
        }
        if (request.amount() == null || request.amount() <= 0) {
            throw new FlashLoanException("INVALID_REQUEST", "amount must be positive",
                    HttpStatus.BAD_REQUEST);
        }
        if (request.timeoutMs() != null && request.timeoutMs() <= 0) {
            throw new FlashLoanException("INVALID_REQUEST", "timeoutMs must be positive",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private long currentConfigVersion() {
        try {
            return configRepository.get().version();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void persistFailure(LoanRequest request, long poolId, String currency,
                                long configVersion, FlashLoanException ex) {
        try {
            loanRepository.recordFailure(request.requestId(), poolId, currency,
                    request.amount(), configVersion,
                    ex.getCode() == null ? "ERROR" : ex.getCode(), ex.getMessage());
        } catch (RuntimeException persistError) {
            persistError.addSuppressed(ex);
            throw persistError;
        }
    }

    // Deterministic replay: the same requestId always yields the same stored
    // outcome. Successful replays return 200; failed replays re-raise the
    // original failure with the same status and message.
    private LoanResponse replay(LoanRecord record) {
        if (LoanRecord.STATUS_SUCCESS.equals(record.status())) {
            return new LoanResponse(record.requestId(), record.status(), record.currency(),
                    record.amount(), record.fee(), record.amountReturned(),
                    record.configVersion(), null);
        }
        String code = record.failureCode() == null ? "REPLAY_FAILED" : record.failureCode();
        throw new FlashLoanException(code, record.failureReason(), HttpStatus.CONFLICT);
    }

    public LoanRecord get(String requestId) {
        return loanRepository.findByRequestId(requestId)
                .orElseThrow(() -> new FlashLoanException("loan not found: " + requestId,
                        HttpStatus.NOT_FOUND));
    }

    public List<LedgerEntry> getLedger(String requestId) {
        get(requestId);
        return ledgerRepository.findByRequestId(requestId);
    }

    public List<LoanRecord> list() {
        return loanRepository.findAll();
    }
}

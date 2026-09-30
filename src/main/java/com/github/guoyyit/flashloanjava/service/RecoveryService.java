package com.github.guoyyit.flashloanjava.service;

import com.github.guoyyit.flashloanjava.domain.LedgerEntry;
import com.github.guoyyit.flashloanjava.domain.LoanRecord;
import com.github.guoyyit.flashloanjava.domain.Pool;
import com.github.guoyyit.flashloanjava.domain.RecoveryReport;
import com.github.guoyyit.flashloanjava.repository.LedgerRepository;
import com.github.guoyyit.flashloanjava.repository.LoanRepository;
import com.github.guoyyit.flashloanjava.repository.PoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final PoolRepository poolRepository;
    private final LoanRepository loanRepository;
    private final LedgerRepository ledgerRepository;

    private volatile RecoveryReport lastReport;

    public RecoveryService(PoolRepository poolRepository,
                           LoanRepository loanRepository,
                           LedgerRepository ledgerRepository) {
        this.poolRepository = poolRepository;
        this.loanRepository = loanRepository;
        this.ledgerRepository = ledgerRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyOnStartup() {
        RecoveryReport report = verify();
        if (report.consistent()) {
            log.info("Recovery check passed: {} pools, {} committed loans, {} failed units",
                    report.poolCount(), report.successLoanCount(), report.failedLoanCount());
        } else {
            log.error("Recovery check detected inconsistencies: {}", report.problems());
        }
    }

    public RecoveryReport verify() {
        List<String> problems = new ArrayList<>();

        List<Pool> pools = poolRepository.findAll();
        List<LedgerEntry> entries = ledgerRepository.findAll();
        List<LoanRecord> loans = loanRepository.findAll();

        // Last ledger balance per account must equal the live pool balance.
        Map<String, Long> lastBalance = new HashMap<>();
        for (LedgerEntry entry : entries) {
            lastBalance.put(entry.account(), entry.balanceAfter());
        }
        for (Pool pool : pools) {
            String account = "pool:" + pool.id();
            Long ledgerBalance = lastBalance.get(account);
            long expected = ledgerBalance == null ? 0 : ledgerBalance;
            if (expected != pool.balance()) {
                problems.add("pool " + pool.id() + " balance " + pool.balance()
                        + " does not match ledger " + expected);
            }
        }

        // No committed state may reference a loan left in RUNNING state; those
        // uncommitted units are rolled back by the database on crash recovery.
        int success = 0;
        int failed = 0;
        for (LoanRecord loan : loans) {
            switch (loan.status()) {
                case LoanRecord.STATUS_SUCCESS -> success++;
                case LoanRecord.STATUS_FAILED -> failed++;
                default -> problems.add("loan " + loan.requestId()
                        + " is in non-terminal state " + loan.status());
            }
            if (LoanRecord.STATUS_SUCCESS.equals(loan.status()) && loan.fee() < 0) {
                problems.add("loan " + loan.requestId() + " has negative fee");
            }
        }

        // Ledger entries may only exist for successful committed units.
        for (LedgerEntry entry : entries) {
            if (entry.requestId() == null) {
                continue;
            }
            boolean known = loans.stream().anyMatch(loan ->
                    loan.requestId().equals(entry.requestId())
                            && LoanRecord.STATUS_SUCCESS.equals(loan.status()));
            if (!known) {
                problems.add("ledger entry from non-committed loan " + entry.requestId());
                break;
            }
        }

        RecoveryReport report = new RecoveryReport(problems.isEmpty(), pools.size(),
                success, failed, List.copyOf(problems));
        this.lastReport = report;
        return report;
    }

    public RecoveryReport lastReport() {
        return verify();
    }
}

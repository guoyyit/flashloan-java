package com.github.guoyyit.flashloanjava.repository;

import com.github.guoyyit.flashloanjava.domain.LoanRecord;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class LoanRepository {

    private static final RowMapper<LoanRecord> LOAN_MAPPER = (rs, rowNum) -> new LoanRecord(
            rs.getString("request_id"),
            rs.getLong("pool_id"),
            rs.getString("currency"),
            rs.getLong("amount"),
            rs.getLong("fee"),
            rs.getLong("amount_returned"),
            rs.getString("status"),
            rs.getLong("config_version"),
            rs.getString("failure_code"),
            rs.getString("failure_reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("completed_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public LoanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<LoanRecord> findByRequestId(String requestId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT request_id, pool_id, currency, amount, fee, amount_returned, status, "
                            + "config_version, failure_code, failure_reason, created_at, completed_at "
                            + "FROM loans WHERE request_id = ?",
                    LOAN_MAPPER, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Transactional
    public void insertRunning(String requestId, long poolId, String currency, long amount, long configVersion) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO loans (request_id, pool_id, currency, amount, fee, amount_returned, status, "
                        + "config_version, failure_code, failure_reason, created_at, completed_at) "
                        + "VALUES (?, ?, ?, ?, 0, 0, 'RUNNING', ?, NULL, NULL, ?, ?)",
                requestId, poolId, currency, amount, configVersion, Timestamp.from(now), Timestamp.from(now));
    }

    @Transactional
    public void markSuccess(String requestId, long fee, long amountReturned) {
        jdbcTemplate.update(
                "UPDATE loans SET status = 'SUCCESS', fee = ?, amount_returned = ?, "
                        + "completed_at = ? WHERE request_id = ?",
                fee, amountReturned, Timestamp.from(Instant.now()), requestId);
    }

    // Failure rows are persisted in a brand-new transaction so they survive even
    // when the caller's (rolled back) loan transaction is the reason of failure.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String requestId, long poolId, String currency, long amount,
                              long configVersion, String failureCode, String failureReason) {
        Instant now = Instant.now();
        String safeReason = failureReason == null ? null
                : failureReason.substring(0, Math.min(failureReason.length(), 500));
        jdbcTemplate.update(
                "MERGE INTO loans (request_id, pool_id, currency, amount, fee, amount_returned, status, "
                        + "config_version, failure_code, failure_reason, created_at, completed_at) "
                        + "KEY (request_id) VALUES (?, ?, ?, ?, 0, 0, 'FAILED', ?, ?, ?, ?, ?)",
                requestId, poolId, currency, amount, configVersion, failureCode, safeReason,
                Timestamp.from(now), Timestamp.from(now));
    }

    public List<LoanRecord> findAll() {
        return jdbcTemplate.query(
                "SELECT request_id, pool_id, currency, amount, fee, amount_returned, status, "
                        + "config_version, failure_code, failure_reason, created_at, completed_at "
                        + "FROM loans ORDER BY created_at, request_id",
                LOAN_MAPPER);
    }
}

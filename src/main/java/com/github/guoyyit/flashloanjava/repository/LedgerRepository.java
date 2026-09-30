package com.github.guoyyit.flashloanjava.repository;

import com.github.guoyyit.flashloanjava.domain.LedgerEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class LedgerRepository {

    private static final RowMapper<LedgerEntry> LEDGER_MAPPER = (rs, rowNum) -> new LedgerEntry(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("seq"),
            rs.getInt("step"),
            rs.getString("account"),
            rs.getString("currency"),
            rs.getLong("delta"),
            rs.getLong("balance_after"),
            rs.getString("action"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public LedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(String requestId, int step, String account, String currency,
                       long delta, long balanceAfter, String action) {
        long seq = nextSeq(account);
        jdbcTemplate.update(
                "INSERT INTO ledger (request_id, seq, step, account, currency, delta, balance_after, "
                        + "action, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                requestId, seq, step, account, currency, delta, balanceAfter, action,
                Timestamp.from(Instant.now()));
    }

    private long nextSeq(String account) {
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), -1) FROM ledger WHERE account = ?", Long.class, account);
        return (max == null ? -1 : max) + 1;
    }

    public List<LedgerEntry> findByRequestId(String requestId) {
        return jdbcTemplate.query(
                "SELECT id, request_id, seq, step, account, currency, delta, balance_after, action, created_at "
                        + "FROM ledger WHERE request_id = ? ORDER BY seq, id",
                LEDGER_MAPPER, requestId);
    }

    public List<LedgerEntry> findAll() {
        return jdbcTemplate.query(
                "SELECT id, request_id, seq, step, account, currency, delta, balance_after, action, created_at "
                        + "FROM ledger ORDER BY seq, id",
                LEDGER_MAPPER);
    }
}

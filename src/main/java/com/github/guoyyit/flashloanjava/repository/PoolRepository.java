package com.github.guoyyit.flashloanjava.repository;

import com.github.guoyyit.flashloanjava.domain.Pool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PoolRepository {

    private static final RowMapper<Pool> POOL_MAPPER = (rs, rowNum) -> new Pool(
            rs.getLong("id"),
            rs.getString("currency"),
            rs.getLong("balance"),
            rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public PoolRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Pool create(String currency, long initialDeposit) {
        Instant now = Instant.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO pools (currency, balance, version, created_at) VALUES (?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, currency);
            ps.setLong(2, initialDeposit);
            ps.setTimestamp(3, Timestamp.from(now));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return new Pool(id, currency, initialDeposit, 0, now);
    }

    public Optional<Pool> findById(long id) {
        List<Pool> pools = jdbcTemplate.query(
                "SELECT id, currency, balance, version, created_at FROM pools WHERE id = ?",
                POOL_MAPPER, id);
        return pools.stream().findFirst();
    }

    public Optional<Pool> findByIdForUpdate(long id) {
        List<Pool> pools = jdbcTemplate.query(
                "SELECT id, currency, balance, version, created_at FROM pools WHERE id = ? FOR UPDATE",
                POOL_MAPPER, id);
        return pools.stream().findFirst();
    }

    public List<Pool> findAllByIdInOrderForUpdate(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        return jdbcTemplate.query(
                "SELECT id, currency, balance, version, created_at FROM pools "
                        + "WHERE id IN (" + placeholders + ") ORDER BY id FOR UPDATE",
                POOL_MAPPER, ids.toArray());
    }

    public List<Pool> findAll() {
        return jdbcTemplate.query(
                "SELECT id, currency, balance, version, created_at FROM pools ORDER BY id",
                POOL_MAPPER);
    }

    public void updateBalance(long id, long newBalance) {
        jdbcTemplate.update(
                "UPDATE pools SET balance = ?, version = version + 1 WHERE id = ?",
                newBalance, id);
    }
}

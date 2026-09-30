package com.github.guoyyit.flashloanjava.repository;

import com.github.guoyyit.flashloanjava.domain.ServiceConfig;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Repository
public class ConfigRepository {

    private static final RowMapper<ServiceConfig> CONFIG_MAPPER = (rs, rowNum) -> new ServiceConfig(
            rs.getLong("id"),
            rs.getBigDecimal("fee_rate"),
            rs.getLong("max_loan_amount"),
            rs.getInt("max_steps"),
            rs.getLong("timeout_ms"),
            rs.getLong("version"),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public ConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ServiceConfig get() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, fee_rate, max_loan_amount, max_steps, timeout_ms, version, updated_at "
                            + "FROM service_config WHERE id = 1",
                    CONFIG_MAPPER);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public ServiceConfig update(BigDecimal feeRate, long maxLoanAmount, int maxSteps, long timeoutMs) {
        jdbcTemplate.update(
                "UPDATE service_config SET fee_rate = ?, max_loan_amount = ?, max_steps = ?, "
                        + "timeout_ms = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = 1",
                feeRate, maxLoanAmount, maxSteps, timeoutMs);
        return get();
    }
}

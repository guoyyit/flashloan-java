package com.github.guoyyit.flashloanjava.service;

import com.github.guoyyit.flashloanjava.domain.ServiceConfig;
import com.github.guoyyit.flashloanjava.repository.ConfigRepository;
import com.github.guoyyit.flashloanjava.web.dto.ConfigUpdateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class ConfigService {

    private final ConfigRepository configRepository;

    public ConfigService(ConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    public ServiceConfig get() {
        return configRepository.get();
    }

    // A config update is one atomic transaction and bumps the version. A loan
    // binds to the config snapshot visible when its transaction starts, so
    // updates never disturb loans already in flight.
    @Transactional
    public ServiceConfig update(ConfigUpdateRequest request) {
        ServiceConfig current = configRepository.get();
        BigDecimal feeRate = request.feeRate() == null ? current.feeRate() : request.feeRate();
        long maxLoanAmount = request.maxLoanAmount() == null
                ? current.maxLoanAmount() : request.maxLoanAmount();
        int maxSteps = request.maxSteps() == null ? current.maxSteps() : request.maxSteps();
        long timeoutMs = request.timeoutMs() == null ? current.timeoutMs() : request.timeoutMs();

        if (feeRate.signum() < 0 || feeRate.compareTo(BigDecimal.ONE) > 0) {
            throw new FlashLoanException("feeRate must be between 0 and 1", HttpStatus.BAD_REQUEST);
        }
        if (maxLoanAmount <= 0) {
            throw new FlashLoanException("maxLoanAmount must be positive", HttpStatus.BAD_REQUEST);
        }
        if (maxSteps <= 0 || maxSteps > 10000) {
            throw new FlashLoanException("maxSteps must be between 1 and 10000", HttpStatus.BAD_REQUEST);
        }
        if (timeoutMs < 1 || timeoutMs > 3_600_000L) {
            throw new FlashLoanException("timeoutMs must be between 1 and 3600000", HttpStatus.BAD_REQUEST);
        }

        return configRepository.update(feeRate, maxLoanAmount, maxSteps, timeoutMs);
    }
}

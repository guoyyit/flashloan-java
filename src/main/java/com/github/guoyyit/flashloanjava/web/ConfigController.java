package com.github.guoyyit.flashloanjava.web;

import com.github.guoyyit.flashloanjava.service.ConfigService;
import com.github.guoyyit.flashloanjava.web.dto.ConfigUpdateRequest;
import com.github.guoyyit.flashloanjava.web.dto.ConfigView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/api/config")
    public ConfigView get() {
        return ConfigView.from(configService.currentConfig());
    }

    @PutMapping("/api/config")
    public ConfigView update(@RequestBody ConfigUpdateRequest request) {
        return ConfigView.from(configService.update(
                request.getFeeRate(),
                request.getMaxLoanAmount(),
                request.getMaxSteps()));
    }
}

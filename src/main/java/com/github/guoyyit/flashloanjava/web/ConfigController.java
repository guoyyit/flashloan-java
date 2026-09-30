package com.github.guoyyit.flashloanjava.web;

import com.github.guoyyit.flashloanjava.domain.RecoveryReport;
import com.github.guoyyit.flashloanjava.domain.ServiceConfig;
import com.github.guoyyit.flashloanjava.service.ConfigService;
import com.github.guoyyit.flashloanjava.service.RecoveryService;
import com.github.guoyyit.flashloanjava.web.dto.ConfigUpdateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;
    private final RecoveryService recoveryService;

    public ConfigController(ConfigService configService, RecoveryService recoveryService) {
        this.configService = configService;
        this.recoveryService = recoveryService;
    }

    @GetMapping
    public ServiceConfig get() {
        return configService.get();
    }

    @PostMapping
    public ServiceConfig update(@RequestBody ConfigUpdateRequest request) {
        return configService.update(request);
    }

    @GetMapping("/recovery")
    public RecoveryReport recovery() {
        return recoveryService.lastReport();
    }
}

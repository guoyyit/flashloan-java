package com.github.guoyyit.flashloanjava.web;

import java.util.List;

import com.github.guoyyit.flashloanjava.service.PoolService;
import com.github.guoyyit.flashloanjava.web.dto.CreatePoolRequest;
import com.github.guoyyit.flashloanjava.web.dto.PoolView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PoolController {

    private final PoolService poolService;

    public PoolController(PoolService poolService) {
        this.poolService = poolService;
    }

    @PostMapping("/api/pools")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public PoolView create(@RequestBody CreatePoolRequest request) {
        return PoolView.from(poolService.createPool(
                request.getId(), request.getName(), request.getBalances()));
    }

    @PostMapping("/api/pools/{poolId}/deposits")
    public PoolView deposit(@PathVariable String poolId,
                            @RequestBody CreatePoolRequest request) {
        return PoolView.from(poolService.addFunds(poolId, request.getBalances()));
    }

    @GetMapping("/api/pools")
    public List<PoolView> list() {
        return poolService.listPools().stream().map(PoolView::from).toList();
    }

    @GetMapping("/api/pools/{poolId}")
    public PoolView get(@PathVariable String poolId) {
        return PoolView.from(poolService.getPool(poolId));
    }
}

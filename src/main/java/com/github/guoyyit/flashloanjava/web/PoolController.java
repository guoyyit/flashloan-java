package com.github.guoyyit.flashloanjava.web;

import com.github.guoyyit.flashloanjava.domain.Pool;
import com.github.guoyyit.flashloanjava.service.PoolService;
import com.github.guoyyit.flashloanjava.web.dto.CreatePoolRequest;
import com.github.guoyyit.flashloanjava.web.dto.DepositRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/pools")
public class PoolController {

    private final PoolService poolService;

    public PoolController(PoolService poolService) {
        this.poolService = poolService;
    }

    @PostMapping
    public Pool create(@RequestBody CreatePoolRequest request) {
        return poolService.create(request);
    }

    @GetMapping
    public List<Pool> list() {
        return poolService.list();
    }

    @GetMapping("/{id}")
    public Pool get(@PathVariable long id) {
        return poolService.get(id);
    }

    @PostMapping("/{id}/deposit")
    public Pool deposit(@PathVariable long id, @RequestBody DepositRequest request) {
        return poolService.deposit(id, request.amount());
    }
}

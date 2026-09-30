package com.github.guoyyit.flashloanjava.web;

import com.github.guoyyit.flashloanjava.domain.LedgerEntry;
import com.github.guoyyit.flashloanjava.domain.LoanRecord;
import com.github.guoyyit.flashloanjava.service.LoanService;
import com.github.guoyyit.flashloanjava.web.dto.LoanRequest;
import com.github.guoyyit.flashloanjava.web.dto.LoanResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    @PostMapping
    public LoanResponse execute(@RequestBody LoanRequest request) {
        return loanService.execute(request);
    }

    @GetMapping
    public List<LoanRecord> list() {
        return loanService.list();
    }

    @GetMapping("/{requestId}")
    public LoanRecord get(@PathVariable String requestId) {
        return loanService.get(requestId);
    }

    @GetMapping("/{requestId}/ledger")
    public List<LedgerEntry> ledger(@PathVariable String requestId) {
        return loanService.getLedger(requestId);
    }
}

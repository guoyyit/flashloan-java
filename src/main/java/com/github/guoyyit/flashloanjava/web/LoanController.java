package com.github.guoyyit.flashloanjava.web;

import java.util.List;

import com.github.guoyyit.flashloanjava.service.LoanService;
import com.github.guoyyit.flashloanjava.web.dto.LoanRequest;
import com.github.guoyyit.flashloanjava.web.dto.LoanView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    @PostMapping("/api/loans")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanView execute(@RequestBody LoanRequest request) {
        return LoanView.from(loanService.executeLoan(request));
    }

    @GetMapping("/api/loans")
    public List<LoanView> list() {
        return loanService.listRecords().stream().map(LoanView::from).toList();
    }

    @GetMapping("/api/loans/{id}")
    public LoanView get(@PathVariable long id) {
        return LoanView.from(loanService.getRecord(id));
    }
}

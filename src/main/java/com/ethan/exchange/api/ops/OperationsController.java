package com.ethan.exchange.api.ops;

import com.ethan.exchange.api.query.ExchangeQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ops")
public class OperationsController {

    private final ExchangeQueryService queries;

    public OperationsController(ExchangeQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/overview")
    public ExchangeQueryService.OperationsView overview() {
        return queries.operations();
    }
}

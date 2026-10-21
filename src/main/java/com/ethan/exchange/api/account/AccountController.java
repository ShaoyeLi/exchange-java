package com.ethan.exchange.api.account;

import com.ethan.exchange.api.query.ExchangeQueryService;
import com.ethan.exchange.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final ExchangeQueryService queries;

    public AccountController(ExchangeQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/balances")
    public List<ExchangeQueryService.BalanceView> balances(
            @AuthenticationPrincipal Jwt jwt) {
        return queries.balances(CurrentUser.id(jwt));
    }

    @GetMapping("/orders")
    public List<ExchangeQueryService.OrderView> orders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "100") int limit) {
        return queries.orders(CurrentUser.id(jwt), limit);
    }

    @GetMapping("/trades")
    public List<ExchangeQueryService.TradeView> trades(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "100") int limit) {
        return queries.trades(CurrentUser.id(jwt), limit);
    }

    @GetMapping("/deposits")
    public List<ExchangeQueryService.DepositView> deposits(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "100") int limit) {
        return queries.deposits(CurrentUser.id(jwt), limit);
    }

    @GetMapping("/withdrawals")
    public List<ExchangeQueryService.WithdrawalView> withdrawals(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "100") int limit) {
        return queries.withdrawals(CurrentUser.id(jwt), limit);
    }
}

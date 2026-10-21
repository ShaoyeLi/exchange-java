package com.ethan.exchange.api.withdrawal;

import com.ethan.exchange.security.CurrentUser;
import com.ethan.exchange.withdrawal.WithdrawalCommand;
import com.ethan.exchange.withdrawal.WithdrawalResult;
import com.ethan.exchange.withdrawal.WithdrawalService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/withdrawals")
public class WithdrawalController {

    private final WithdrawalService withdrawals;

    public WithdrawalController(WithdrawalService withdrawals) {
        this.withdrawals = withdrawals;
    }

    @PostMapping
    public WithdrawalResult request(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateWithdrawalRequest request) {
        return withdrawals.request(new WithdrawalCommand(
                CurrentUser.id(jwt), request.asset(), request.destinationAddress(),
                request.amount(), idempotencyKey));
    }

    @PostMapping("/{withdrawalId}/cancel")
    public WithdrawalResult cancel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long withdrawalId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return withdrawals.cancel(CurrentUser.id(jwt), withdrawalId, idempotencyKey);
    }
}

package com.ethan.exchange.api;

import com.ethan.exchange.matching.domain.DomainException;
import com.ethan.exchange.order.application.ExchangeErrorCode;
import com.ethan.exchange.order.application.ExchangeOperationException;
import com.ethan.exchange.withdrawal.WithdrawalErrorCode;
import com.ethan.exchange.withdrawal.WithdrawalException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ExchangeOperationException.class)
    ProblemDetail orderError(ExchangeOperationException exception) {
        HttpStatus status = switch (exception.code()) {
            case ORDER_NOT_FOUND, MARKET_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT, CONCURRENT_UPDATE, ORDER_NOT_ACTIVE -> HttpStatus.CONFLICT;
            case ORDER_NOT_OWNED -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_REQUEST;
        };
        return problem(status, exception.code().name(), exception.getMessage());
    }

    @ExceptionHandler(WithdrawalException.class)
    ProblemDetail withdrawalError(WithdrawalException exception) {
        HttpStatus status = switch (exception.code()) {
            case ASSET_NOT_FOUND, WITHDRAWAL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case WITHDRAWAL_NOT_OWNED -> HttpStatus.FORBIDDEN;
            case IDEMPOTENCY_CONFLICT, WITHDRAWAL_NOT_CANCELABLE, CONCURRENT_UPDATE ->
                    HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return problem(status, exception.code().name(), exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ProblemDetail badRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(DomainException.class)
    ProblemDetail domainError(DomainException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.code().name(), exception.getMessage());
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    ProblemDetail notFound(EmptyResultDataAccessException exception) {
        return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "requested resource was not found");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(code);
        problem.setProperty("code", code);
        return problem;
    }
}

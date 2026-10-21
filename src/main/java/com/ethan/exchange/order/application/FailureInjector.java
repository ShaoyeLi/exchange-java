package com.ethan.exchange.order.application;

@FunctionalInterface
public interface FailureInjector {

    FailureInjector NONE = ignored -> { };

    void check(FailurePoint point);
}

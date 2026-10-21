package com.ethan.exchange.chain;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountScalerTest {

    @Test
    void convertsEqualHigherAndLowerTokenScalesExactly() {
        assertThat(AmountScaler.toInternal(BigInteger.valueOf(123), 6, 6)).isEqualTo(123);
        assertThat(AmountScaler.toInternal(BigInteger.valueOf(12_300), 8, 6)).isEqualTo(123);
        assertThat(AmountScaler.toInternal(BigInteger.valueOf(123), 4, 6)).isEqualTo(12_300);
    }

    @Test
    void rejectsDustZeroAndLongOverflow() {
        assertThatThrownBy(() -> AmountScaler.toInternal(BigInteger.valueOf(123), 8, 6))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> AmountScaler.toInternal(BigInteger.ZERO, 6, 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AmountScaler.toInternal(
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), 6, 6))
                .isInstanceOf(ArithmeticException.class);
    }
}

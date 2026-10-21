package com.ethan.exchange.order.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExactMathTest {

    @Test
    void cumulativeDifferenceMakesFragmentedFeesEqualWholeOrderFee() {
        long firstTotal = ExactMath.cumulativeFee(1, 10, 0, 20);
        long finalTotal = ExactMath.cumulativeFee(1_000, 10, 0, 20);

        assertThat(firstTotal).isEqualTo(1);
        assertThat(finalTotal - firstTotal).isZero();
        assertThat(finalTotal).isEqualTo(ExactMath.cumulativeFee(1_000, 10, 0, 20));
    }

    @Test
    void mixedMakerAndTakerHistoryUsesWeightedCumulativeCeiling() {
        assertThat(ExactMath.cumulativeFee(10_001, 10, 20_001, 20)).isEqualTo(51);
        assertThat(ExactMath.remainingFeeBuffer(
                100_000_000, 10, 0, 20,
                110_000_000, 20, 200_000)).isEqualTo(120_000);
    }

    @Test
    void zeroAndMaximumBasisPointsAreAccepted() {
        assertThat(ExactMath.cumulativeFee(100, 0, 0, 0)).isZero();
        assertThat(ExactMath.cumulativeFee(100, 10_000, 0, 0)).isEqualTo(100);
    }

    @Test
    void overflowAndInvalidBasisPointsFailExplicitly() {
        assertThatThrownBy(() -> ExactMath.quote(Long.MAX_VALUE, 2))
                .isInstanceOf(ExchangeOperationException.class)
                .extracting("code").isEqualTo(ExchangeErrorCode.NUMERIC_OVERFLOW);
        assertThatThrownBy(() -> ExactMath.cumulativeFee(Long.MAX_VALUE, 10_000, 1, 10_000))
                .isInstanceOf(ExchangeOperationException.class);
        assertThatThrownBy(() -> ExactMath.cumulativeFee(1, 10_001, 0, 0))
                .isInstanceOf(ExchangeOperationException.class);
    }
}

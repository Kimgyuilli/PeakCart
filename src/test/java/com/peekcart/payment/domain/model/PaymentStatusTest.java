package com.peekcart.payment.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

    static Stream<Arguments> allowedTransitions() {
        return Stream.of(
                Arguments.of(PaymentStatus.PENDING, PaymentStatus.APPROVED, true),
                Arguments.of(PaymentStatus.PENDING, PaymentStatus.FAILED, true),
                Arguments.of(PaymentStatus.APPROVED, PaymentStatus.FAILED, false),
                Arguments.of(PaymentStatus.APPROVED, PaymentStatus.PENDING, false),
                Arguments.of(PaymentStatus.FAILED, PaymentStatus.APPROVED, false),
                Arguments.of(PaymentStatus.FAILED, PaymentStatus.PENDING, false)
        );
    }

    @ParameterizedTest(name = "{0} → {1} = {2}")
    @MethodSource("allowedTransitions")
    @DisplayName("PaymentStatus 전이 규칙 검증")
    void canTransitionTo(PaymentStatus from, PaymentStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }
}

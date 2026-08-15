package com.peekcart.payment.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PaymentStatus 상태 전이 규칙 테스트")
class PaymentStatusTest {

    @Test
    @DisplayName("PENDING → APPROVED 전이가 허용된다")
    void pending_toApproved_allowed() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.APPROVED)).isTrue();
    }

    @Test
    @DisplayName("PENDING → FAILED 전이가 허용된다")
    void pending_toFailed_allowed() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.FAILED)).isTrue();
    }

    @Test
    @DisplayName("PENDING → PENDING 전이가 거부된다")
    void pending_toPending_denied() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.PENDING)).isFalse();
    }

    @Test
    @DisplayName("APPROVED → REFUNDED 만 허용된다 (ADR-0018 D2 — 이전에는 terminal 이었다)")
    void approved_onlyToRefunded_allowed() {
        for (PaymentStatus target : PaymentStatus.values()) {
            assertThat(PaymentStatus.APPROVED.canTransitionTo(target))
                    .as("APPROVED → %s", target)
                    .isEqualTo(target == PaymentStatus.REFUNDED);
        }
    }

    @Test
    @DisplayName("REFUNDED 는 종결 — 모든 전이가 거부된다")
    void refunded_allTransitions_denied() {
        for (PaymentStatus target : PaymentStatus.values()) {
            assertThat(PaymentStatus.REFUNDED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    @DisplayName("CANCELLED 는 종결 — 모든 전이가 거부된다")
    void cancelled_allTransitions_denied() {
        for (PaymentStatus target : PaymentStatus.values()) {
            assertThat(PaymentStatus.CANCELLED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    @DisplayName("FAILED에서 모든 전이가 거부된다")
    void failed_allTransitions_denied() {
        for (PaymentStatus target : PaymentStatus.values()) {
            assertThat(PaymentStatus.FAILED.canTransitionTo(target)).isFalse();
        }
    }
}

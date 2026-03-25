package com.peekcart.payment.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.domain.exception.PaymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    @Test
    @DisplayName("create: PENDING 상태로 생성되고 UUID paymentKey가 부여된다")
    void create() {
        Payment payment = Payment.create(1L, 10_000L);

        assertThat(payment.getOrderId()).isEqualTo(1L);
        assertThat(payment.getAmount()).isEqualTo(10_000L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getPaymentKey()).isNotBlank();
        assertThat(payment.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("assignPaymentKey: paymentKey를 교체한다")
    void assignPaymentKey() {
        Payment payment = Payment.create(1L, 10_000L);
        payment.assignPaymentKey("toss_key_abc");

        assertThat(payment.getPaymentKey()).isEqualTo("toss_key_abc");
    }

    @Test
    @DisplayName("approve: APPROVED 상태로 전이되고 method/approvedAt이 설정된다")
    void approve() {
        Payment payment = Payment.create(1L, 10_000L);
        LocalDateTime now = LocalDateTime.now();

        payment.approve("CARD", now);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getMethod()).isEqualTo("CARD");
        assertThat(payment.getApprovedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("approve: PENDING 외 상태에서 호출하면 PAY_004 예외")
    void approve_whenNotPending_throws() {
        Payment payment = Payment.create(1L, 10_000L);
        payment.approve("CARD", LocalDateTime.now());

        assertThatThrownBy(() -> payment.approve("CARD", LocalDateTime.now()))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).getErrorCode()).isEqualTo(ErrorCode.PAY_004));
    }

    @Test
    @DisplayName("fail: FAILED 상태로 전이된다")
    void fail() {
        Payment payment = Payment.create(1L, 10_000L);

        payment.fail();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("fail: PENDING 외 상태에서 호출하면 PAY_004 예외")
    void fail_whenNotPending_throws() {
        Payment payment = Payment.create(1L, 10_000L);
        payment.fail();

        assertThatThrownBy(() -> payment.fail())
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).getErrorCode()).isEqualTo(ErrorCode.PAY_004));
    }

    @Test
    @DisplayName("validateAmount: 금액이 일치하면 예외 없음")
    void validateAmount_success() {
        Payment payment = Payment.create(1L, 10_000L);
        payment.validateAmount(10_000L);
    }

    @Test
    @DisplayName("validateAmount: 금액이 불일치하면 PAY_001 예외")
    void validateAmount_mismatch_throws() {
        Payment payment = Payment.create(1L, 10_000L);

        assertThatThrownBy(() -> payment.validateAmount(9_000L))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).getErrorCode()).isEqualTo(ErrorCode.PAY_001));
    }
}

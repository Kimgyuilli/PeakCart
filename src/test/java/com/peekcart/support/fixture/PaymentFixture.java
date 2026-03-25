package com.peekcart.support.fixture;

import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.domain.model.Payment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * Payment 도메인 테스트 픽스처 팩토리.
 */
public class PaymentFixture {

    public static final Long DEFAULT_PAYMENT_ID = 1L;
    public static final Long DEFAULT_ORDER_ID = 1L;
    public static final String DEFAULT_PAYMENT_KEY = "toss_pay_key_abc123";
    public static final long DEFAULT_AMOUNT = 100_000L;
    public static final String DEFAULT_METHOD = "CARD";

    private PaymentFixture() {}

    public static Payment payment() {
        return Payment.create(DEFAULT_ORDER_ID, DEFAULT_AMOUNT);
    }

    public static Payment paymentWithId() {
        Payment payment = payment();
        ReflectionTestUtils.setField(payment, "id", DEFAULT_PAYMENT_ID);
        return payment;
    }

    public static Payment approvedPayment() {
        Payment payment = paymentWithId();
        payment.assignPaymentKey(DEFAULT_PAYMENT_KEY);
        payment.approve(DEFAULT_METHOD, LocalDateTime.of(2026, 3, 25, 12, 0, 0));
        return payment;
    }

    public static ConfirmPaymentCommand confirmPaymentCommand() {
        return new ConfirmPaymentCommand(DEFAULT_PAYMENT_KEY, DEFAULT_ORDER_ID, DEFAULT_AMOUNT);
    }

    public static PaymentDetailDto paymentDetailDto(Payment payment) {
        return PaymentDetailDto.from(payment);
    }
}

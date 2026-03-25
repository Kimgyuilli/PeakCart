package com.peekcart.payment.domain.event;

/**
 * 결제 실패 이벤트.
 */
public record PaymentFailedEvent(
        Long paymentId,
        Long orderId,
        String paymentKey,
        long amount
) {
}

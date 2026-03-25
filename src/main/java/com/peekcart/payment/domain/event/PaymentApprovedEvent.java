package com.peekcart.payment.domain.event;

/**
 * 결제 승인 완료 이벤트.
 */
public record PaymentApprovedEvent(
        Long paymentId,
        Long orderId,
        String paymentKey,
        long amount,
        String method
) {
}

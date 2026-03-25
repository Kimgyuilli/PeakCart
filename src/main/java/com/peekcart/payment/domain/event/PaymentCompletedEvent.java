package com.peekcart.payment.domain.event;

/**
 * 결제 승인 완료 이벤트. 설계 문서의 payment.completed 토픽에 대응한다.
 */
public record PaymentCompletedEvent(
        Long paymentId,
        Long orderId,
        String paymentKey,
        long amount,
        String method
) {
}

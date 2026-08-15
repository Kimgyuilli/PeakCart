package com.peekcart.global.outbox.dto;

import java.time.LocalDateTime;

/**
 * 환불 결과 회신 이벤트({@code payment.refunded}) payload (ADR-0018 D1).
 * Payment 가 발행하고 Order·Product·Notification 이 소비한다. 파티션 키 = {@code orderId}.
 *
 * <p>소비자는 {@code result} 에 따라 서로 다른 종착 상태로 간다(ADR-0018 D4) — 성공만 "해결됨"이며
 * 실패는 "닫혔지만 해결되지 않음"이다.
 *
 * @param orderId        주문 PK (파티션 키)
 * @param userId         주문 소유자. Notification 이 payload 에서 직접 읽으므로 <b>필수</b>다
 *                       (orderId 로 사용자를 조회할 계약이 없다)
 * @param result         {@code SUCCEEDED} 또는 {@code FAILED}. {@code UNRESOLVED} 는 발행하지 않는다
 * @param refundedAmount 환불 금액 (성공 시). 실패 시 null
 * @param failureCode    실패 사유 코드 (실패 시). 성공 시 null
 * @param resolvedAt     확정 시각
 */
public record PaymentRefundedPayload(
        Long orderId,
        Long userId,
        RefundResult result,
        Long refundedAmount,
        String failureCode,
        LocalDateTime resolvedAt
) {
}

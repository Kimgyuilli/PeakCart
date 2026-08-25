package com.peekcart.global.outbox.dto;

import java.time.LocalDateTime;

/**
 * 환불 보상 요청 이벤트 payload (ADR-0018 D1). 요청 토픽 2종이 <b>같은 스키마</b>를 쓴다 —
 * producer 는 다르지만(1 topic = 1 producer) 계약은 하나다.
 *
 * <p><b>금액을 싣지 않는다.</b> 환불 금액의 결정 주체는 Payment 이며({@code payments.amount}),
 * 발행자가 실은 금액을 Payment 가 신뢰하면 같은 사실에 대한 소스가 둘로 갈라진다(ADR-0018 D1).
 *
 * @param orderId    주문 PK (파티션 키)
 * @param reason     보상 사유. 발행자가 명시한다
 * @param detectedAt 감지 시각 (발행 시각이 아니라 <b>감지</b> 시각 — 원장 기록과 같은 트랜잭션이다)
 */
public record CompensationRequestedPayload(
        Long orderId,
        CompensationReason reason,
        LocalDateTime detectedAt
) {
}

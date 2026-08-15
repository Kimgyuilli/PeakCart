package com.peekcart.global.outbox.dto;

/**
 * 환불 결과 (ADR-0018 D1/D2). {@code payment.refunded} 의 필수 필드다.
 *
 * <p>{@code UNRESOLVED} 는 <b>회신으로 발행되지 않는다</b> — 결과가 확정되지 않은 상태이며,
 * Payment 의 reconciliation 이 확정한 뒤에야 {@code SUCCEEDED}/{@code FAILED} 로 발행된다.
 * enum 에 값이 있는 이유는 원장(payment_refunds)과 계약 값의 이름을 일치시키기 위함이다.
 */
public enum RefundResult {

    /** PG 취소 성공 확정. */
    SUCCEEDED,

    /** 영구 실패 확정 — 재시도로 상태가 바뀌지 않는다. */
    FAILED,

    /** 결과 불명(회신 미발행). */
    UNRESOLVED
}

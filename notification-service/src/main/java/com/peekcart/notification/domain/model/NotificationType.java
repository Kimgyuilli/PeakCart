package com.peekcart.notification.domain.model;

/**
 * 알림 유형. notifications 테이블의 type 컬럼에 대응한다.
 */
public enum NotificationType {
    ORDER_CREATED,
    PAYMENT_COMPLETED,
    PAYMENT_FAILED,
    ORDER_CANCELLED,

    /**
     * 환불 완료 (ADR-0018 D6). {@code payment.refunded(SUCCEEDED)} 만 이 알림을 만든다 —
     * 실패·미확정은 내부 미결 상태이며 사용자에게 전가하지 않는다.
     */
    PAYMENT_REFUNDED
}

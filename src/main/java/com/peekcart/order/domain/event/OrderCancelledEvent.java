package com.peekcart.order.domain.event;

/**
 * 주문 취소 이벤트. Notification 도메인에서 소비한다.
 */
public record OrderCancelledEvent(Long orderId, Long userId, String orderNumber) {
}

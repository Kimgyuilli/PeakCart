package com.peekcart.order.domain.event;

/**
 * 주문 생성 이벤트. Payment/Notification 도메인에서 소비한다.
 */
public record OrderCreatedEvent(Long orderId, Long userId, String orderNumber, long totalAmount) {
}

package com.peekcart.order.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.port.ProductPort;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final OrderRepository orderRepository;
    private final ProductPort productPort;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment.completed", groupId = "order-svc-payment-completed-group")
    @Transactional
    public void handlePaymentCompleted(String message) {
        JsonNode payload = extractPayload(message);
        Long orderId = payload.get("orderId").asLong();

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
        order.transitionTo(OrderStatus.PAYMENT_COMPLETED);
        log.debug("주문 상태 전이 → PAYMENT_COMPLETED — orderId={}", orderId);
    }

    @KafkaListener(topics = "payment.failed", groupId = "order-svc-payment-failed-group")
    @Transactional
    public void handlePaymentFailed(String message) {
        JsonNode payload = extractPayload(message);
        Long orderId = payload.get("orderId").asLong();

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
        order.cancel();
        for (var item : order.getOrderItems()) {
            productPort.restoreStock(item.getProductId(), item.getQuantity());
        }
        log.debug("결제 실패로 주문 취소 + 재고 복구 — orderId={}", orderId);
    }

    private JsonNode extractPayload(String message) {
        try {
            return objectMapper.readTree(message).get("payload");
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Kafka 메시지 역직렬화 실패", e);
        }
    }
}

package com.peekcart.order.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.idempotency.IdempotencyChecker;
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

/**
 * 결제 관련 Kafka 이벤트를 소비하여 주문 상태를 전이하는 Consumer.
 * <p>
 * 소비 토픽: {@code payment.completed}, {@code payment.failed}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final OrderRepository orderRepository;
    private final ProductPort productPort;
    private final IdempotencyChecker idempotencyChecker;
    private final ObjectMapper objectMapper;

    /** 결제 성공 시 주문 상태를 {@code PAYMENT_COMPLETED}로 전이한다. */
    @KafkaListener(topics = "payment.completed", groupId = "order-svc-payment-completed-group")
    @Transactional
    public void handlePaymentCompleted(String message) {
        JsonNode root = parseMessage(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, "order-svc-payment-completed-group", () -> {
            Long orderId = payload.get("orderId").asLong();
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
            order.transitionTo(OrderStatus.PAYMENT_COMPLETED);
            log.debug("주문 상태 전이 → PAYMENT_COMPLETED — orderId={}", orderId);
        });
    }

    /** 결제 실패 시 주문을 취소하고 재고를 복구한다. */
    @KafkaListener(topics = "payment.failed", groupId = "order-svc-payment-failed-group")
    @Transactional
    public void handlePaymentFailed(String message) {
        JsonNode root = parseMessage(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, "order-svc-payment-failed-group", () -> {
            Long orderId = payload.get("orderId").asLong();
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
            order.cancel();
            for (var item : order.getOrderItems()) {
                productPort.restoreStock(item.getProductId(), item.getQuantity());
            }
            log.debug("결제 실패로 주문 취소 + 재고 복구 — orderId={}", orderId);
        });
    }

    private JsonNode parseMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            if (root.get("eventId") == null) {
                throw new IllegalArgumentException("Kafka 메시지에 eventId 필드가 없습니다: " + message);
            }
            if (root.get("payload") == null) {
                throw new IllegalArgumentException("Kafka 메시지에 payload 필드가 없습니다: " + message);
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Kafka 메시지 역직렬화 실패", e);
        }
    }
}

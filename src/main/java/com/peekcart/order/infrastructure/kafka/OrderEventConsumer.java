package com.peekcart.order.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
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

    private static final String GROUP_PAYMENT_COMPLETED = "order-svc-payment-completed-group";
    private static final String GROUP_PAYMENT_FAILED = "order-svc-payment-failed-group";

    private final OrderRepository orderRepository;
    private final ProductPort productPort;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser kafkaMessageParser;

    /** 결제 성공 시 주문 상태를 {@code PAYMENT_COMPLETED}로 전이한다. */
    @KafkaListener(topics = "payment.completed", groupId = GROUP_PAYMENT_COMPLETED)
    @Transactional
    public void handlePaymentCompleted(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_PAYMENT_COMPLETED, () -> {
            Long orderId = payload.get("orderId").asLong();
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
            order.transitionTo(OrderStatus.PAYMENT_COMPLETED);
            log.debug("주문 상태 전이 → PAYMENT_COMPLETED — orderId={}", orderId);
        });
    }

    /** 결제 실패 시 주문을 취소하고 재고를 복구한다. */
    @KafkaListener(topics = "payment.failed", groupId = GROUP_PAYMENT_FAILED)
    @Transactional
    public void handlePaymentFailed(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_PAYMENT_FAILED, () -> {
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
}

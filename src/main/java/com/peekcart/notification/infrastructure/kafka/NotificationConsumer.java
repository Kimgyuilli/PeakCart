package com.peekcart.notification.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.notification.application.NotificationCommandService;
import com.peekcart.notification.domain.model.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final NotificationCommandService notificationCommandService;
    private final IdempotencyChecker idempotencyChecker;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.created", groupId = "notification-svc-order-created-group")
    @Transactional
    public void handleOrderCreated(String message) {
        JsonNode root = parseMessage(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, "notification-svc-order-created-group", () -> {
            Long userId = payload.get("userId").asLong();
            String orderNumber = payload.get("orderNumber").asText();
            long totalAmount = payload.get("totalAmount").asLong();
            String msg = String.format("주문이 생성되었습니다. [주문번호: %s, 금액: %,d원]", orderNumber, totalAmount);
            notificationCommandService.createNotification(userId, NotificationType.ORDER_CREATED, msg);
        });
    }

    @KafkaListener(topics = "payment.completed", groupId = "notification-svc-payment-completed-group")
    @Transactional
    public void handlePaymentCompleted(String message) {
        JsonNode root = parseMessage(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, "notification-svc-payment-completed-group", () -> {
            Long userId = payload.get("userId").asLong();
            Long orderId = payload.get("orderId").asLong();
            long amount = payload.get("amount").asLong();
            String method = payload.get("method").asText();
            String msg = String.format("결제가 완료되었습니다. [주문 ID: %d, 금액: %,d원, 결제수단: %s]",
                    orderId, amount, method);
            notificationCommandService.createNotification(userId, NotificationType.PAYMENT_COMPLETED, msg);
        });
    }

    @KafkaListener(topics = "payment.failed", groupId = "notification-svc-payment-failed-group")
    @Transactional
    public void handlePaymentFailed(String message) {
        JsonNode root = parseMessage(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, "notification-svc-payment-failed-group", () -> {
            Long userId = payload.get("userId").asLong();
            Long orderId = payload.get("orderId").asLong();
            long amount = payload.get("amount").asLong();
            String msg = String.format("결제에 실패했습니다. [주문 ID: %d, 금액: %,d원]", orderId, amount);
            notificationCommandService.createNotification(userId, NotificationType.PAYMENT_FAILED, msg);
        });
    }

    @KafkaListener(topics = "order.cancelled", groupId = "notification-svc-order-cancelled-group")
    @Transactional
    public void handleOrderCancelled(String message) {
        JsonNode root = parseMessage(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, "notification-svc-order-cancelled-group", () -> {
            Long userId = payload.get("userId").asLong();
            String orderNumber = payload.get("orderNumber").asText();
            String msg = String.format("주문이 취소되었습니다. [주문번호: %s]", orderNumber);
            notificationCommandService.createNotification(userId, NotificationType.ORDER_CANCELLED, msg);
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

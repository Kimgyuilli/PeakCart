package com.peekcart.payment.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 관련 Kafka 이벤트를 소비하여 결제를 생성하는 Consumer.
 * <p>
 * 소비 토픽: {@code order.created}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final PaymentRepository paymentRepository;
    private final IdempotencyChecker idempotencyChecker;
    private final ObjectMapper objectMapper;

    /** 주문 생성 시 {@code PENDING} 상태의 Payment를 생성한다. */
    @KafkaListener(topics = "order.created", groupId = "payment-svc-order-created-group")
    @Transactional
    public void handleOrderCreated(String message) {
        JsonNode root = parseMessage(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, "payment-svc-order-created-group", () -> {
            Long orderId = payload.get("orderId").asLong();
            long totalAmount = payload.get("totalAmount").asLong();
            Payment payment = Payment.create(orderId, totalAmount);
            paymentRepository.save(payment);
            log.debug("Payment(PENDING) 생성 — orderId={}", orderId);
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

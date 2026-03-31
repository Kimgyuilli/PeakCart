package com.peekcart.payment.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.created", groupId = "payment-svc-order-created-group")
    @Transactional
    public void handleOrderCreated(String message) {
        JsonNode payload = extractPayload(message);
        Long orderId = payload.get("orderId").asLong();
        long totalAmount = payload.get("totalAmount").asLong();

        Payment payment = Payment.create(orderId, totalAmount);
        paymentRepository.save(payment);
        log.debug("Payment(PENDING) 생성 — orderId={}", orderId);
    }

    private JsonNode extractPayload(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode payload = root.get("payload");
            if (payload == null) {
                throw new IllegalArgumentException("Kafka 메시지에 payload 필드가 없습니다: " + message);
            }
            return payload;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Kafka 메시지 역직렬화 실패", e);
        }
    }
}

package com.peekcart.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
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

    private static final String GROUP_ORDER_CREATED = "payment-svc-order-created-group";

    private final PaymentRepository paymentRepository;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser kafkaMessageParser;

    /** 주문 생성 시 {@code PENDING} 상태의 Payment를 생성한다. */
    @KafkaListener(topics = "order.created", groupId = GROUP_ORDER_CREATED)
    @Transactional
    public void handleOrderCreated(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_ORDER_CREATED, () -> {
            Long orderId = payload.get("orderId").asLong();
            long totalAmount = payload.get("totalAmount").asLong();
            Payment payment = Payment.create(orderId, totalAmount);
            paymentRepository.save(payment);
            log.debug("Payment(PENDING) 생성 — orderId={}", orderId);
        });
    }
}

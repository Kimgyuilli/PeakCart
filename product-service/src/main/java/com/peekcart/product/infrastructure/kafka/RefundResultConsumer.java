package com.peekcart.product.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.product.application.StockReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * {@code payment.refunded} 를 소비해 예약 원장의 환불 종결을 기록하는 Consumer (ADR-0018 D4).
 *
 * <p>{@code compensated_at}(감지 marker) 과 종결은 다른 사실이다 — 감지만 남기면 "환불이
 * 어떻게 끝났는지"를 원장에서 알 수 없다. 확정된 결과만 기록하고 {@code UNRESOLVED} 는
 * 전이하지 않는다: 확정되지 않은 결과로 원장을 닫으면 그 원장이 거짓이 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundResultConsumer {

    private static final String GROUP_PAYMENT_REFUNDED = "product-svc-payment-refunded-group";

    private final StockReservationService reservationService;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser kafkaMessageParser;

    @KafkaListener(topics = "payment.refunded", groupId = GROUP_PAYMENT_REFUNDED)
    @Transactional
    public void handlePaymentRefunded(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_PAYMENT_REFUNDED, () -> {
            Long orderId = payload.get("orderId").asLong();
            String result = readText(payload, "result");
            if (!"SUCCEEDED".equals(result) && !"FAILED".equals(result)) {
                // UNRESOLVED · 모르는 값 · 필드 부재 — 확정 회신이 뒤따른다.
                log.warn("확정되지 않은 환불 회신 — 기록 없음, orderId={}, result={}", orderId, result);
                return;
            }
            reservationService.recordRefundResult(orderId, result,
                    readText(payload, "failureCode"), readResolvedAt(payload));
        });
    }

    /** 문자열 필드를 읽는다. 부재/null 은 {@code null} — 구 메시지 내성(ADR-0012 D2). */
    private String readText(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * 확정 시각을 읽는다. 부재/null 이면 소비 시각으로 대체한다 — 종결 시각이 비면 "닫혔는데
     * 언제인지 모르는" 원장이 되어 운영 조회가 불가능해진다.
     */
    private LocalDateTime readResolvedAt(JsonNode payload) {
        String raw = readText(payload, "resolvedAt");
        return raw == null ? LocalDateTime.now() : LocalDateTime.parse(raw);
    }
}

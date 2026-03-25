package com.peekcart.payment.application;

import com.peekcart.payment.domain.model.WebhookLog;
import com.peekcart.payment.domain.repository.WebhookLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Toss 웹훅 이벤트를 처리하는 애플리케이션 서비스.
 * idempotency_key로 중복 처리를 방지한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class WebhookService {

    private final WebhookLogRepository webhookLogRepository;

    /**
     * 웹훅을 처리하고 로그를 저장한다.
     * 이미 처리된 idempotencyKey면 스킵한다.
     */
    public void processWebhook(String paymentKey, String eventType, String idempotencyKey, String payload) {
        if (webhookLogRepository.existsByIdempotencyKey(idempotencyKey)) {
            return;
        }
        WebhookLog log = WebhookLog.create(paymentKey, eventType, idempotencyKey, payload, "PROCESSED");
        webhookLogRepository.save(log);
    }
}

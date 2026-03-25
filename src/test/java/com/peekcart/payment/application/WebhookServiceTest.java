package com.peekcart.payment.application;

import com.peekcart.payment.domain.model.WebhookLog;
import com.peekcart.payment.domain.repository.WebhookLogRepository;
import com.peekcart.support.ServiceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ServiceTest
@DisplayName("WebhookService 단위 테스트")
class WebhookServiceTest {

    @InjectMocks WebhookService webhookService;
    @Mock WebhookLogRepository webhookLogRepository;

    @Test
    @DisplayName("processWebhook: 정상 처리 시 WebhookLog를 저장한다")
    void processWebhook_success() {
        given(webhookLogRepository.existsByIdempotencyKey("key-001")).willReturn(false);

        webhookService.processWebhook("toss_key", "PAYMENT_STATUS_CHANGED", "key-001", "{}");

        then(webhookLogRepository).should().save(any(WebhookLog.class));
    }

    @Test
    @DisplayName("processWebhook: 이미 처리된 idempotencyKey면 저장하지 않고 스킵한다")
    void processWebhook_duplicate_skipped() {
        given(webhookLogRepository.existsByIdempotencyKey("key-001")).willReturn(true);

        webhookService.processWebhook("toss_key", "PAYMENT_STATUS_CHANGED", "key-001", "{}");

        then(webhookLogRepository).should(never()).save(any());
    }
}

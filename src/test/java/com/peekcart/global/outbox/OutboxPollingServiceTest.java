package com.peekcart.global.outbox;

import com.peekcart.global.port.SlackPort;
import com.peekcart.support.ServiceTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ServiceTest
@DisplayName("OutboxPollingService 단위 테스트")
class OutboxPollingServiceTest {

    @InjectMocks OutboxPollingService outboxPollingService;
    @Mock OutboxEventRepository outboxEventRepository;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock SlackPort slackPort;

    private OutboxEvent event;

    @BeforeEach
    void setUp() {
        event = OutboxEvent.create("Order", "1", "order.created", eventId -> "{}");
        // MAX_RETRY=5. 다음 폴링에서 Kafka 실패 → retryCount 5 도달 → markFailed + Slack 알림 경로 진입
        ReflectionTestUtils.setField(event, "retryCount", 4);
    }

    @Test
    @DisplayName("Slack 알림 실패 시에도 FAILED 상태 저장이 수행되고 예외가 전파되지 않는다")
    void slackFailureIsIsolated() {
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(event));
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willThrow(new RuntimeException("Kafka down"));
        willThrow(new RuntimeException("Slack down")).given(slackPort).send(anyString());

        assertThatCode(() -> outboxPollingService.pollAndPublish()).doesNotThrowAnyException();

        ArgumentCaptor<OutboxEvent> savedCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(savedCaptor.getValue().getRetryCount()).isEqualTo(5);
        verify(slackPort, times(1)).send(anyString());
    }

    @Test
    @DisplayName("MAX_RETRY 도달 시 Slack 알림이 정상 발송되면 FAILED 상태로 저장된다")
    void slackNotifiedOnMaxRetryReached() {
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(event));
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willThrow(new RuntimeException("Kafka down"));

        outboxPollingService.pollAndPublish();

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackPort).send(msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("[Outbox FAILED]", event.getEventId());
        verify(outboxEventRepository).save(any(OutboxEvent.class));
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
    }
}

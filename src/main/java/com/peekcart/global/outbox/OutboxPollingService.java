package com.peekcart.global.outbox;

import com.peekcart.global.port.SlackPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPollingService {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRY = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SlackPort slackPort;

    public void pollAndPublish() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(BATCH_SIZE);

        for (OutboxEvent event : pendingEvents) {
            try {
                kafkaTemplate.send(event.getEventType(), event.getAggregateId(), event.getPayload())
                        .get();
                event.markPublished();
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("Outbox 이벤트 Kafka 발행 실패 — eventId={}, eventType={}: {}",
                        event.getEventId(), event.getEventType(), e.getMessage());
                event.incrementRetry();

                if (event.getRetryCount() >= MAX_RETRY) {
                    event.markFailed();
                    try {
                        slackPort.send(String.format(
                                "[Outbox FAILED] eventId=%s, eventType=%s, retryCount=%d",
                                event.getEventId(), event.getEventType(), event.getRetryCount()));
                    } catch (Exception slackEx) {
                        log.warn("Outbox FAILED Slack 알림 발송 실패 — eventId={}",
                                event.getEventId(), slackEx);
                    }
                }

                outboxEventRepository.save(event);
            }
        }
    }
}

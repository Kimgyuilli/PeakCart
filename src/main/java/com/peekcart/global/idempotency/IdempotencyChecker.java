package com.peekcart.global.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyChecker {

    private final ProcessedEventRepository processedEventRepository;

    public boolean executeIfNew(String eventId, String consumerGroup, Runnable action) {
        if (processedEventRepository.exists(eventId, consumerGroup)) {
            log.debug("중복 이벤트 무시 — eventId={}, consumerGroup={}", eventId, consumerGroup);
            return false;
        }
        action.run();
        processedEventRepository.save(ProcessedEvent.create(eventId, consumerGroup));
        return true;
    }
}

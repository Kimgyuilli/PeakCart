package com.peekcart.global.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Kafka Consumer 멱등성 처리기.
 * {@code processed_events} 테이블을 조회하여 중복 이벤트를 필터링하고,
 * 신규 이벤트만 비즈니스 로직을 실행한 뒤 처리 이력을 기록한다.
 * <p>
 * 호출자의 {@code @Transactional} 컨텍스트에 참여하여
 * 비즈니스 로직 + 처리 이력 기록의 원자성을 보장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyChecker {

    private final ProcessedEventRepository processedEventRepository;

    /**
     * 해당 이벤트가 미처리 상태이면 비즈니스 로직을 실행하고 처리 이력을 기록한다.
     *
     * @param eventId       Kafka 메시지의 이벤트 ID
     * @param consumerGroup Kafka Consumer Group ID
     * @param action        실행할 비즈니스 로직
     * @return 실행 여부. 중복 이벤트이면 {@code false}
     */
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

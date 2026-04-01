package com.peekcart.global.idempotency;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProcessedEventRepositoryImpl implements ProcessedEventRepository {

    private final ProcessedEventJpaRepository processedEventJpaRepository;

    @Override
    public boolean exists(String eventId, String consumerGroup) {
        return processedEventJpaRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup);
    }

    @Override
    public ProcessedEvent save(ProcessedEvent event) {
        return processedEventJpaRepository.save(event);
    }
}

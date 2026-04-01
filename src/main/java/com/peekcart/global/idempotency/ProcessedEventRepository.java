package com.peekcart.global.idempotency;

public interface ProcessedEventRepository {

    boolean exists(String eventId, String consumerGroup);

    ProcessedEvent save(ProcessedEvent event);
}

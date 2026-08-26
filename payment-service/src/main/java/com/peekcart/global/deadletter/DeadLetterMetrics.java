package com.peekcart.global.deadletter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * DLQ 원장 잔량 메트릭 (계획 ④-d-1 P3 · 부모 P11).
 *
 * <p><b>{@link DeadLetterEndpoint} 와 같은 쿼리를 쓴다.</b> 두 표면이 서로 다른 방식으로 세면
 * 값이 갈라졌을 때 어느 쪽이 맞는지 알 수 없다 — 그러면 둘 다 신뢰할 수 없게 된다.
 *
 * <p>actuator 조회 표면은 유지한다. 메트릭은 시계열·alert 용이고, actuator 는 운영자가 지금
 * 바로 물어보는 용도다(④-c-2a §2.6-E).
 *
 * <p><b>토픽·group 별 분해는 두지 않는다.</b> 태그로 달면 시계열이 (토픽 × group) 으로 늘어난다.
 * 잔량 2종으로 "쌓이고 있는가" 를 답할 수 있고, 무엇이 쌓이는지는 원장을 조회하면 된다.
 */
@Component
public class DeadLetterMetrics {

    public DeadLetterMetrics(MeterRegistry registry, DeadLetterRecordJpaRepository repository) {
        Gauge.builder("dlq.backlog", repository, DeadLetterRecordJpaRepository::countUnresolved)
                .description("미결(OPEN/ACKED) DLQ 원장 건수")
                .register(registry);

        Gauge.builder("dlq.oldest.age", repository, DeadLetterMetrics::oldestAgeSeconds)
                .description("가장 오래된 미결 DLQ 원장의 경과 시간(초). 미결 0건이면 0")
                .baseUnit("seconds")
                .register(registry);
    }

    private static double oldestAgeSeconds(DeadLetterRecordJpaRepository repository) {
        return repository.findOldestUnresolvedOccurredAt()
                .map(oldest -> (double) Duration.between(oldest, LocalDateTime.now()).toSeconds())
                .orElse(0.0);
    }
}

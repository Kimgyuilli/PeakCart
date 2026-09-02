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
 * <p><b>집계 단위는 행이 아니라 incident(root) 다</b>(ADR-0020 §D6-3). 재발행이 실패할 때마다 자식 행이
 * 늘어나므로 행으로 세면 backlog·oldest-age 가 사건 수보다 계속 부풀고 alert 임계값이 의미를 잃는다.
 * PromQL 식(`k8s/monitoring/shared/grafana-alerts.yml`)은 메트릭 이름만 참조하므로 <b>변경이 없다</b>.
 *
 * <p><b>토픽·group 별 분해는 두지 않는다.</b> 태그로 달면 시계열이 (토픽 × group) 으로 늘어난다.
 * 잔량 2종으로 "쌓이고 있는가" 를 답할 수 있고, 무엇이 쌓이는지는 원장을 조회하면 된다.
 */
@Component
public class DeadLetterMetrics {

    public DeadLetterMetrics(MeterRegistry registry, DeadLetterRecordJpaRepository repository) {
        Gauge.builder("dlq.backlog", repository, DeadLetterRecordJpaRepository::countUnresolved)
                .description("미결(OPEN/ACKED) DLQ incident 건수 — 재발행 재실패로 늘어난 자식 행은 세지 않는다")
                .register(registry);

        Gauge.builder("dlq.oldest.age", repository, DeadLetterMetrics::oldestAgeSeconds)
                .description("가장 오래된 미결 DLQ incident 의 경과 시간(초). 미결 0건이면 0")
                .baseUnit("seconds")
                .register(registry);
    }

    private static double oldestAgeSeconds(DeadLetterRecordJpaRepository repository) {
        return repository.findOldestUnresolvedOccurredAt()
                .map(oldest -> (double) Duration.between(oldest, LocalDateTime.now()).toSeconds())
                .orElse(0.0);
    }
}

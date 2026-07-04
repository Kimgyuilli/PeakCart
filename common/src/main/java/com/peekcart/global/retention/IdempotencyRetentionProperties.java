package com.peekcart.global.retention;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * processed_events 멱등성 보존 정책 (ADR-0012 D5 · 구현 ② PR3).
 *
 * <p>보존기간({@code retention})은 D5 의 4개 창(floor)의 {@code max} 이상이어야 한다 — 교차필드 불변식.
 * 미만이면 {@link AssertTrue} 위반으로 <b>부팅 실패(fail-fast)</b>. 단순 필드 제약이 아니라 필드 간 비교이므로
 * {@code @AssertTrue} cross-field validator 로 강제한다.
 *
 * <p>소유 서비스(product/order/payment/notification)만 {@code @EnableConfigurationProperties} 로 활성화한다.
 * user-service 는 활성화하지 않으므로 floor 검증도 돌지 않는다(cleanup 잡 미소유).
 * 설정키는 전부 base {@code application.yml} 소유(동작 정책 — ADR-0007).
 */
@ConfigurationProperties(prefix = "app.idempotency")
@Validated
@Getter
@Setter
public class IdempotencyRetentionProperties {

    /** processed_events 보존기간. floor 4창의 max 이상이어야 한다. */
    @NotNull
    private Duration retention;

    @Valid
    private final Floor floor = new Floor();

    @Valid
    private final Cleanup cleanup = new Cleanup();

    @AssertTrue(message = "app.idempotency.retention 은 floor 4창"
            + "(kafka-topic-retention·max-consumer-downtime·dlq-replay-window·backfill-replay-window)의 "
            + "max 이상이어야 합니다 (ADR-0012 D5 멱등성 창 보호)")
    public boolean isRetentionAtLeastFloor() {
        return retention != null && retention.compareTo(floor.max()) >= 0;
    }

    /** D5 retention floor 입력 4창. */
    @Getter
    @Setter
    public static class Floor {
        @NotNull
        private Duration kafkaTopicRetention;
        @NotNull
        private Duration maxConsumerDowntime;
        @NotNull
        private Duration dlqReplayWindow;
        @NotNull
        private Duration backfillReplayWindow;

        /** 4창 중 최댓값. null 은 무시(누락 창은 @NotNull 이 별도로 잡는다). */
        public Duration max() {
            return Stream.of(kafkaTopicRetention, maxConsumerDowntime, dlqReplayWindow, backfillReplayWindow)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(Duration.ZERO);
        }
    }

    /** 배치 삭제 계약 — outbox cleanup 도 동일 계약을 공유한다(구현 ② PR3). */
    @Getter
    @Setter
    public static class Cleanup {
        /** 한 batch 당 삭제 상한(unbounded DELETE 방지). */
        private int batchSize = 500;
        /** 한 실행당 batch 반복 상한. */
        private int maxBatchesPerRun = 20;
    }
}

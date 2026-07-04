package com.peekcart.global.retention;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * outbox_events 보존 정책 (ADR-0012 D5 · 구현 ② PR3).
 *
 * <p>PUBLISHED 상태 + {@code published_at} 이 {@code retention} 경과한 행만 cleanup 삭제한다.
 * PENDING·FAILED·{@code published_at IS NULL} 행은 보존한다(유실 금지). 배치 삭제 계약은
 * {@link IdempotencyRetentionProperties.Cleanup} 를 공유한다.
 *
 * <p>발행 서비스(product/order/payment)만 {@code @EnableConfigurationProperties} 로 활성화한다.
 * 설정키는 base {@code application.yml} 소유(동작 정책 — ADR-0007).
 */
@ConfigurationProperties(prefix = "app.outbox")
@Validated
@Getter
@Setter
public class OutboxRetentionProperties {

    /** PUBLISHED outbox_events 보존기간. */
    @NotNull
    private Duration retention;
}

package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.RefundStatus;
import com.peekcart.payment.domain.repository.PaymentRefundRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 미해결 환불 backlog 관측 (ADR-0018 D6).
 *
 * <p>"미결로 남기지 않는다"는 계약은 <b>미해결 건수와 그 나이가 보여야</b> 검증 가능하다.
 * Slack 은 배포 구성상 no-op 일 수 있으므로 관측성의 대체물이 아니다.
 */
@Component
public class RefundBacklogMetrics {

    private static final RefundStatus[] BACKLOG_STATUSES = {
            RefundStatus.REQUESTED, RefundStatus.CLAIMED, RefundStatus.UNRESOLVED
    };

    public RefundBacklogMetrics(MeterRegistry meterRegistry, PaymentRefundRepository refundRepository) {
        for (RefundStatus status : BACKLOG_STATUSES) {
            String tag = status.name().toLowerCase();

            Gauge.builder("payment.refund.backlog", refundRepository, repo -> repo.countByStatus(status))
                    .description("미해결 환불 원장 건수 (scrape 시점 집계)")
                    .tag("status", tag)
                    .register(meterRegistry);

            Gauge.builder("payment.refund.oldest.age", refundRepository, repo -> oldestAgeSeconds(repo, status))
                    .description("해당 상태에서 가장 오래된 환불 요청의 경과 시간(초)")
                    .baseUnit("seconds")
                    .tag("status", tag)
                    .register(meterRegistry);
        }
    }

    private static double oldestAgeSeconds(PaymentRefundRepository repository, RefundStatus status) {
        return repository.findOldestRequestedAt(status)
                .map(requestedAt -> (double) Duration.between(requestedAt, LocalDateTime.now()).toSeconds())
                .orElse(0.0);
    }
}

package com.peekcart.global.deadletter;

import com.peekcart.global.port.SlackPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DLQ 원장 유지보수 — 종결 건 정리 + 미결 경보 (계획 ④-c-2a P10).
 *
 * <p><b>{@code OPEN}/{@code ACKED} 는 삭제하지 않는다.</b> 장기 미결은 용량 문제가 아니라
 * 운영 SLA 문제이고, 지우면 그 사실 자체가 사라진다. 대신 age·건수 경보로 사람을 부른다.
 *
 * <p><b>임계값을 설정으로 고정한 이유</b>: "오래되면 경보한다" 만 적으면 mock 호출 1회로 통과하는
 * false-green 이 된다. {@code stale-after}·{@code backlog-threshold}·{@code cooldown} 이 계약이다.
 *
 * <p><b>cooldown 은 인스턴스 로컬이다.</b> 재기동하면 초기화되고 replica 마다 독립이다 —
 * Slack 은 보조 신호이므로(ADR-0018 D6) 이 근사를 받아들인다. 내구적 신호는 원장 행 자체이고
 * {@link DeadLetterEndpoint} 로 언제든 조회할 수 있다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(DeadLetterProperties.class)
@RequiredArgsConstructor
public class DeadLetterMaintenanceScheduler {

    private final DeadLetterRecordJpaRepository repository;
    private final DeadLetterProperties properties;
    private final SlackPort slackPort;

    private final AtomicReference<Instant> lastAlertAt = new AtomicReference<>(Instant.EPOCH);

    /**
     * 종결 건 정리. unbounded DELETE 가 큰 테이블에서 장기 락을 만들지 않도록
     * cutoff 를 1회 계산하고 작은 batch 를 반복한다 (기존 cleanup 계약 승계).
     */
    @Scheduled(cron = "${app.dead-letter.purge.cron:0 40 3 * * *}")
    @SchedulerLock(name = "deadLetterPurgeJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void purge() {
        LocalDateTime cutoff = LocalDateTime.now().minus(properties.getPurge().getRetention());
        int batchSize = properties.getPurge().getBatchSize();
        int maxBatches = properties.getPurge().getMaxBatchesPerRun();

        long total = 0;
        for (int i = 0; i < maxBatches; i++) {
            List<DeadLetterRecord> batch =
                    repository.findPurgeable(cutoff, PageRequest.of(0, batchSize));
            if (batch.isEmpty()) {
                break;
            }
            repository.deleteAll(batch);
            total += batch.size();
            if (batch.size() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("dead_letter_records purge: {}건 삭제 (DISCARDED cutoff={})", total, cutoff);
        }
    }

    /** 미결 경보. 임계값 둘 중 하나라도 넘으면 1회 알린다 (cooldown 내 재발송 없음). */
    @Scheduled(cron = "${app.dead-letter.alert.cron:0 0 * * * *}")
    @SchedulerLock(name = "deadLetterAlertJob", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void alertOnUnresolved() {
        DeadLetterProperties.Alert alert = properties.getAlert();
        long backlog = repository.countUnresolved();

        LocalDateTime staleThreshold = LocalDateTime.now().minus(alert.getStaleAfter());
        List<DeadLetterRecord> stale =
                repository.findStaleUnresolved(staleThreshold, PageRequest.of(0, alert.getScanLimit()));

        boolean backlogBreached = backlog > alert.getBacklogThreshold();
        boolean staleBreached = !stale.isEmpty();
        if (!backlogBreached && !staleBreached) {
            return;
        }

        if (!tryConsumeCooldown(alert.getCooldown())) {
            log.debug("DLQ 미결 경보 cooldown 중 — 발송 생략 (backlog={}, stale={})", backlog, stale.size());
            return;
        }

        String message = String.format(
                "[DLQ 미결] backlog=%d건 (임계 %d) · %s 초과 미결=%d건%n"
                        + "가장 오래된 건: %s%n"
                        + "조치: docs/runbooks/dlq-recovery.md",
                backlog, alert.getBacklogThreshold(), alert.getStaleAfter(), stale.size(),
                stale.isEmpty() ? "-" : describe(stale.get(0)));

        log.warn("DLQ 미결 경보 — backlog={}, stale={}", backlog, stale.size());
        try {
            slackPort.send(message);
        } catch (Exception e) {
            log.warn("DLQ 미결 경보 발송 실패", e);
        }
    }

    /** cooldown 이 지났으면 소비하고 true. 경합 시 한쪽만 통과한다. */
    private boolean tryConsumeCooldown(Duration cooldown) {
        Instant now = Instant.now();
        Instant previous = lastAlertAt.get();
        if (previous.plus(cooldown).isAfter(now)) {
            return false;
        }
        return lastAlertAt.compareAndSet(previous, now);
    }

    private String describe(DeadLetterRecord record) {
        return String.format("topic=%s, partition=%d, offset=%d, group=%s, occurredAt=%s",
                record.getOriginTopic(), record.getOriginPartition(), record.getOriginOffset(),
                record.getFailedConsumerGroup(), record.getOccurredAt());
    }
}

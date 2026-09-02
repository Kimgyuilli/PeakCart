package com.peekcart.global.deadletter;

import com.peekcart.global.outbox.OutboxEventJpaRepository;
import com.peekcart.global.outbox.OutboxEventStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * DLQ 원장 <b>발행 축</b> reconciler (ADR-0020 §D6-4 · 구현 ④-c-2b-2 P12).
 *
 * <p>{@code publication_status} 를 전이시키는 <b>유일한 주체</b>다. 관리 API 는 {@code REQUESTED} 까지만
 * 만들고(진입점, ④-c-2b-4), 그 뒤의 사실은 outbox 가 정한다 — 발행 결과를 아는 것은 poller 뿐이기 때문이다.
 *
 * <p><b>사건 축({@code status})은 건드리지 않는다.</b> 발행 성공은 사건 해소가 아니다(§D6-2).
 * 두 축을 물리적으로 나눈 이유가 여기서 지켜진다 — 이 클래스가 {@code RESOLVED} 를 쓸 수 있게 되는 순간
 * "broker ack 로 종결" 이 뒷문으로 들어온다.
 *
 * <h2>outbox 행 부재를 실패로 추론하지 않는다</h2>
 * 행이 없다는 것은 <b>실패의 증거가 아니다</b>. 이미 발행된 행이 레거시 cleanup·수동 삭제·정합성 결함으로
 * 사라져도 똑같이 관측된다. 자동으로 {@code PUBLISH_FAILED} 로 강등하면 <b>발행된 사건을 "실패" 로 감사
 * 기록하고 재요청까지 열어준다</b> — 같은 메시지가 두 번 발행된다. §D6-4 는 outbox 가 <b>실제 {@code FAILED}
 * 로 소진된 경우에만</b> 그 전이를 정의한다.
 *
 * <p>정상 경로에서는 cleanup 제외 조건
 * ({@link OutboxEventJpaRepository#deletePublishedBatchOlderThan})이 부재를 만들지 않는다. 따라서 부재가
 * 관측되면 그것 자체가 <b>계약 위반 신호</b>이며, 경보를 남기고 사람의 판정 대상으로 둔다(계획 §10 R7).
 */
@Slf4j
@Component
@EnableConfigurationProperties(DeadLetterProperties.class)
@RequiredArgsConstructor
public class DeadLetterPublicationReconciler {

    private final DeadLetterRecordJpaRepository repository;
    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final DeadLetterProperties properties;

    /**
     * {@code REQUESTED} 행의 발행 결과를 outbox 에서 읽어 종착 상태로 옮긴다.
     *
     * <p>주기를 짧게 두는 이유: 이 전이가 늦어지면 사건 종결(I-1 가드)이 그만큼 막힌다.
     * outbox poller 와 같은 주기 축에 둔다.
     */
    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "deadLetterPublicationReconcileJob", lockAtMostFor = "PT5M", lockAtLeastFor = "PT4S")
    @Transactional
    public void reconcile() {
        List<DeadLetterRecord> requested = repository.findRequestedPublications(
                PageRequest.of(0, properties.getReconcile().getBatchSize()));

        for (DeadLetterRecord record : requested) {
            Long outboxEventId = record.getOutboxEventId();
            if (outboxEventId == null) {
                // REQUESTED 인데 연결이 없다 — 진입점이 두 쓰기를 한 트랜잭션에 넣으므로 정상 경로에서 불가능하다.
                log.error("[DLQ-RECONCILE] REQUESTED 원장 행에 outbox_event_id 가 없다 — recordId={}", record.getId());
                continue;
            }

            Optional<OutboxEventStatus> status = outboxEventJpaRepository.findStatusById(outboxEventId);
            if (status.isEmpty()) {
                // fail-closed: 강등하지 않고 그대로 둔다. 다음 사이클에 다시 잡히므로 경보가 계속 울린다.
                log.error("[DLQ-RECONCILE] outbox 행이 없다 — 계약 위반 신호이므로 PUBLISH_FAILED 로 강등하지 않는다. "
                        + "recordId={}, outboxEventId={}", record.getId(), outboxEventId);
                continue;
            }

            settle(record, status.get());
        }
    }

    // PENDING 은 전이 대상이 아니다 — 아직 발행 중이다. 여기서 종착시키면 진행 중인 건이 조기 종결된다.
    private void settle(DeadLetterRecord record, OutboxEventStatus outboxStatus) {
        PublicationStatus settled = switch (outboxStatus) {
            case PUBLISHED -> PublicationStatus.PUBLISHED;
            case FAILED -> PublicationStatus.PUBLISH_FAILED;
            default -> null;
        };
        if (settled == null) {
            return;
        }
        if (record.settlePublication(settled)) {
            log.info("[DLQ-RECONCILE] 발행 축 전이 — recordId={}, outboxEventId={}, {}",
                    record.getId(), record.getOutboxEventId(), settled);
        }
    }
}

package com.peekcart.global.deadletter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqPayloads;
import com.peekcart.global.port.SlackPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DLQ 원장 적재 (계획 ④-c-2a P6·P7·P9).
 *
 * <p><b>적재는 멱등하다.</b> {@code INSERT IGNORE} 가 신규 1 / 중복 0 을 돌려주며, 중복이면
 * 시도 횟수만 올린다. 유니크 위반을 catch 하지 않는 이유는 ④-c-1a 선례 — JPA 에서는 flush 시점과
 * rollback-only 때문에 "충돌 잡아서 no-op" 이 성립하지 않는다.
 *
 * <p><b>알림은 best-effort 다.</b> DB commit 과 Slack 호출은 한 트랜잭션이 아니므로 commit 후
 * 사망하면 알림이 0회다. "정확히 1회" 도 at-least-once 도 주장하지 않는다 — <b>내구적 신호는
 * 원장 행 자체</b>이고, 그게 이 기능의 존재 이유다(ADR-0018 D6 도 Slack 을 보조 신호로 규정).
 * 그래서 Slack 실패가 적재를 실패시키지 않는다.
 *
 * <p><b>민감정보 정책</b>(P11):
 * <ul>
 *   <li><b>헤더는 저장하지 않는다.</b> {@code DlqOrigin} 이 표준 {@code DLT_*} 에서 뽑은 값만
 *       담으므로 {@code X-User-Id} 등 application 헤더는 애초에 원장에 들어오지 않는다 —
 *       제외 목록을 관리할 필요가 없도록 <b>화이트리스트 구조</b>로 막았다.</li>
 *   <li><b>payload 는 상한까지만</b> 저장하고 초과분은 잘라 {@code payloadTruncated} 로 표시한다.
 *       진단용이며 replay 원본이 아니다 — replay 는 원본 토픽 좌표에서 읽는다(④-c-2b).</li>
 *   <li><b>Slack 에는 식별자와 runbook 링크만</b> 보낸다. 채널은 원장보다 접근 범위가 넓고
 *       본문에는 주문/사용자 정보가 섞일 수 있다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(DeadLetterProperties.class)
public class DeadLetterRecorder {

    private final DeadLetterRecordJpaRepository repository;
    private final DeadLetterProperties properties;
    private final SlackPort slackPort;
    private final ObjectMapper objectMapper;

    /**
     * DLQ 레코드 1건을 원장에 적재한다.
     *
     * @return 신규 적재면 true (알림 발송함), 이미 있으면 false
     */
    @Transactional
    public boolean record(DlqOrigin origin) {
        int generation = properties.generationOf(origin.originTopic());
        DlqPayloads.Truncation payload =
                DlqPayloads.truncate(origin.payload(), properties.getPayload().getMaxLength());
        String eventId = DlqPayloads.extractEventId(objectMapper, origin.payload());

        DeadLetterRecord candidate = DeadLetterRecord.open(
                properties.getClusterId(), generation, origin, eventId, payload.value(), payload.truncated());

        int inserted = repository.insertIfAbsent(
                candidate.getClusterId(), candidate.getTopicGeneration(),
                candidate.getOriginTopic(), candidate.getOriginPartition(), candidate.getOriginOffset(),
                candidate.getFailedConsumerGroup(), candidate.getOriginKind().name(),
                candidate.getEventId(), candidate.getOriginalKey(), candidate.getOriginalTimestamp(),
                candidate.getPayload(), candidate.isPayloadTruncated(),
                candidate.getExceptionType(), candidate.getExceptionMessage(),
                candidate.getOccurredAt());

        if (inserted == 0) {
            repository.incrementAttempt(
                    candidate.getClusterId(), candidate.getTopicGeneration(),
                    candidate.getOriginTopic(), candidate.getOriginPartition(),
                    candidate.getOriginOffset(), candidate.getFailedConsumerGroup());
            log.info("DLQ 원장 중복 유입 — topic={}, partition={}, offset={}, group={}",
                    origin.originTopic(), origin.originPartition(), origin.originOffset(),
                    origin.failedConsumerGroup());
            return false;
        }

        log.error("DLQ 원장 신규 적재 — topic={}, partition={}, offset={}, group={}, kind={}, exception={}",
                origin.originTopic(), origin.originPartition(), origin.originOffset(),
                origin.failedConsumerGroup(), origin.originKind(), origin.exceptionType());
        notifyBestEffort(origin, generation);
        return true;
    }

    /**
     * 식별자와 runbook 링크만 보낸다. <b>payload·exception 원문은 넣지 않는다</b> —
     * Slack 채널은 원장보다 접근 범위가 넓고, 그 본문에는 개인정보가 섞일 수 있다(P9).
     */
    private void notifyBestEffort(DlqOrigin origin, int generation) {
        String message = String.format(
                "[DLQ 원장] 신규 미결 1건 — cluster=%s, gen=%d, topic=%s, partition=%d, offset=%d, group=%s, kind=%s%n"
                        + "조치: docs/runbooks/dlq-recovery.md",
                properties.getClusterId(), generation, origin.originTopic(), origin.originPartition(),
                origin.originOffset(), origin.failedConsumerGroup(), origin.originKind());
        try {
            slackPort.send(message);
        } catch (Exception e) {
            // 알림 실패가 적재를 되돌리면 안 된다 — 원장이 내구적 신호다.
            log.warn("DLQ 원장 적재 알림 발송 실패 (적재는 성공)", e);
        }
    }
}

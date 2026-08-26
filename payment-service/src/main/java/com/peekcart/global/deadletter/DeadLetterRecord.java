package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqOriginKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * DLQ 원장 (계획 ④-c-2a P3). "DLQ 로 빠졌다"를 <b>영속</b> 사실로 남긴다.
 *
 * <p>기존에는 {@code kafkaErrorHandler} 의 로그와 Slack 알림뿐이었다. 알림은 휘발성이라
 * <b>미결 건이 아직 남아있는지 판정할 수 없다</b> — 그게 이 테이블의 존재 이유다.
 *
 * <p><b>물리 식별자는 6컬럼이며 전부 NOT NULL 이다</b>(§2.5). {@code origin_kind} 가
 * {@link DlqOriginKind#RESOLVED_ORIGIN} 이면 좌표는 원본 토픽의 것이고,
 * {@link DlqOriginKind#DLQ_ORIGIN} 이면 DLQ 레코드 자신의 것이다. "판독 불가면 NULL" 로 두면
 * MySQL UNIQUE 가 NULL 끼리 충돌시키지 않아 같은 poison record 가 여러 행이 된다.
 *
 * <p>{@code cluster_id}/{@code topic_generation} 이 키에 들어간 이유: {@code (topic, partition,
 * offset, group)} 은 <b>토픽 재생성 시 유일하지 않다</b>. 동명 재생성이면 offset 이 0부터 재사용되어
 * 과거 행과 충돌하고, {@code INSERT IGNORE} 때문에 <b>새 실패가 정상 중복처럼 조용히 폐기</b>된다.
 *
 * <p><b>{@code payload} 는 진단용이다.</b> 상한 초과 시 절단되며 {@code payload_truncated} 로 표시한다.
 * 원본 복원(replay)은 ④-c-2b 소관이고 그 원본은 이 컬럼이 아니라 원본 토픽의 좌표에서 읽는다.
 */
@Entity
@Table(name = "dead_letter_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeadLetterRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- 물리 식별자 6종 (UNIQUE, 전부 NOT NULL) ---

    @Column(name = "cluster_id", nullable = false, length = 60)
    private String clusterId;

    @Column(name = "topic_generation", nullable = false)
    private int topicGeneration;

    @Column(name = "origin_topic", nullable = false, length = 120)
    private String originTopic;

    @Column(name = "origin_partition", nullable = false)
    private int originPartition;

    @Column(name = "origin_offset", nullable = false)
    private long originOffset;

    @Column(name = "failed_consumer_group", nullable = false, length = 120)
    private String failedConsumerGroup;

    // --- 좌표의 출처 ---

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_kind", nullable = false, length = 20)
    private DlqOriginKind originKind;

    // --- 진단 정보 ---

    /** 원본 메시지의 eventId. 파싱 불가·필드 부재면 null — 이 값은 검색 보조키일 뿐 식별자가 아니다. */
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "original_key", length = 255)
    private String originalKey;

    @Column(name = "original_timestamp")
    private Long originalTimestamp;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "payload_truncated", nullable = false)
    private boolean payloadTruncated;

    @Column(name = "exception_type", length = 255)
    private String exceptionType;

    @Column(name = "exception_message", length = 2000)
    private String exceptionMessage;

    // --- 상태 ---

    /**
     * {@link DeadLetterStatus} 의 name. enum 매핑이 아니라 문자열인 이유는 ④-c-2b 가
     * {@code REPLAY_*}/{@code RESOLVED} 를 <b>마이그레이션 없이</b> 추가할 수 있게 하기 위함이다(§2.6-A).
     */
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledged_by", length = 120)
    private String acknowledgedBy;

    @Column(name = "discarded_at")
    private LocalDateTime discardedAt;

    @Column(name = "discarded_by", length = 120)
    private String discardedBy;

    @Column(name = "note", length = 1000)
    private String note;

    private DeadLetterRecord(String clusterId, int topicGeneration, DlqOrigin origin,
                             String eventId, String payload, boolean payloadTruncated) {
        this.clusterId = clusterId;
        this.topicGeneration = topicGeneration;
        this.originKind = origin.originKind();
        this.originTopic = origin.originTopic();
        this.originPartition = origin.originPartition();
        this.originOffset = origin.originOffset();
        this.failedConsumerGroup = origin.failedConsumerGroup();
        this.eventId = eventId;
        this.originalKey = origin.originalKey();
        this.originalTimestamp = origin.originalTimestamp();
        this.payload = payload;
        this.payloadTruncated = payloadTruncated;
        this.exceptionType = origin.exceptionType();
        this.exceptionMessage = origin.exceptionMessage();
        this.status = DeadLetterStatus.OPEN.name();
        this.attemptCount = 1;
        // 저장소 정밀도(DATETIME(6))로 맞춰 기록한다 — MySQL 은 초과 자릿수를 반올림하므로
        // 나노초를 그대로 두면 인메모리 값과 저장된 값이 최대 1μs 어긋난다 (④-c-1b 전례).
        this.occurredAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static DeadLetterRecord open(String clusterId, int topicGeneration, DlqOrigin origin,
                                        String eventId, String payload, boolean payloadTruncated) {
        return new DeadLetterRecord(clusterId, topicGeneration, origin, eventId, payload, payloadTruncated);
    }

    public DeadLetterStatus statusValue() {
        return DeadLetterStatus.valueOf(status);
    }

    /**
     * 운영자가 확인했음을 기록한다. 이미 종결된 건은 no-op — 종결을 되돌리지 않는다.
     *
     * @return 실제로 전이했으면 true
     */
    public boolean acknowledge(String actor) {
        if (statusValue() != DeadLetterStatus.OPEN) {
            return false;
        }
        this.status = DeadLetterStatus.ACKED.name();
        this.acknowledgedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        this.acknowledgedBy = actor;
        return true;
    }

    /**
     * 재처리하지 않기로 종결한다. <b>사유가 없으면 거부한다</b> — 근거 없이 닫힌 원장은
     * "해결됨" 과 구분되지 않아 거짓말을 한다.
     *
     * @return 실제로 전이했으면 true
     */
    public boolean discard(String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("DISCARDED 는 사유 기록이 필수입니다");
        }
        if (statusValue().isTerminal()) {
            return false;
        }
        this.status = DeadLetterStatus.DISCARDED.name();
        this.discardedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        this.discardedBy = actor;
        this.note = reason;
        return true;
    }
}

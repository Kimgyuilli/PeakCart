package com.peekcart.global.deadletter;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DeadLetterRecordJpaRepository extends JpaRepository<DeadLetterRecord, Long> {

    /**
     * 원장 적재 (계획 ④-c-2a P6). <b>단일 원자 INSERT</b> — 유니크 충돌을 예외가 아니라
     * 영향 행 수 0 으로 돌려받아야 소비 트랜잭션이 rollback-only 로 오염되지 않는다.
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id = id} 를 쓰지 않는 이유(④-c-1a 실측): MySQL Connector/J 는
     * 기본이 <b>found-rows</b> 시맨틱이라 값이 바뀌지 않은 중복도 <b>1</b> 로 보고한다 — 그러면
     * 중복 유입에도 "신규 적재" 로 판단해 알림이 중복 발송된다. {@code INSERT IGNORE} 는 건너뛴 행을
     * 0 으로 보고한다.
     *
     * @return 1 이면 신규 적재(알림 대상), 0 이면 이미 있음(no-op)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO dead_letter_records
                (cluster_id, topic_generation, origin_topic, origin_partition, origin_offset,
                 failed_consumer_group, origin_kind, event_id, original_key, original_timestamp,
                 payload, payload_truncated, exception_type, exception_message,
                 status, attempt_count, occurred_at)
            VALUES (:clusterId, :topicGeneration, :originTopic, :originPartition, :originOffset,
                    :failedConsumerGroup, :originKind, :eventId, :originalKey, :originalTimestamp,
                    :payload, :payloadTruncated, :exceptionType, :exceptionMessage,
                    'OPEN', 1, :occurredAt)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("clusterId") String clusterId,
                       @Param("topicGeneration") int topicGeneration,
                       @Param("originTopic") String originTopic,
                       @Param("originPartition") int originPartition,
                       @Param("originOffset") long originOffset,
                       @Param("failedConsumerGroup") String failedConsumerGroup,
                       @Param("originKind") String originKind,
                       @Param("eventId") String eventId,
                       @Param("originalKey") String originalKey,
                       @Param("originalTimestamp") Long originalTimestamp,
                       @Param("payload") String payload,
                       @Param("payloadTruncated") boolean payloadTruncated,
                       @Param("exceptionType") String exceptionType,
                       @Param("exceptionMessage") String exceptionMessage,
                       @Param("occurredAt") LocalDateTime occurredAt);

    /**
     * 같은 좌표가 다시 유입됐을 때 시도 횟수만 올린다. 새 행을 만들지 않는다 —
     * 같은 사실이 여러 행이면 미결 건수가 부풀어 운영 판단이 틀어진다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE dead_letter_records
               SET attempt_count = attempt_count + 1
             WHERE cluster_id = :clusterId AND topic_generation = :topicGeneration
               AND origin_topic = :originTopic AND origin_partition = :originPartition
               AND origin_offset = :originOffset AND failed_consumer_group = :failedConsumerGroup
            """, nativeQuery = true)
    int incrementAttempt(@Param("clusterId") String clusterId,
                         @Param("topicGeneration") int topicGeneration,
                         @Param("originTopic") String originTopic,
                         @Param("originPartition") int originPartition,
                         @Param("originOffset") long originOffset,
                         @Param("failedConsumerGroup") String failedConsumerGroup);

    Optional<DeadLetterRecord> findByClusterIdAndTopicGenerationAndOriginTopicAndOriginPartitionAndOriginOffsetAndFailedConsumerGroup(
            String clusterId, int topicGeneration, String originTopic,
            int originPartition, long originOffset, String failedConsumerGroup);

    /** 미결 건수 (actuator 조회 표면 · 건수 상한 경보). */
    @Query("SELECT COUNT(r) FROM DeadLetterRecord r WHERE r.status IN ('OPEN', 'ACKED')")
    long countUnresolved();

    /** 가장 오래된 미결 건의 발생 시각 (age 경보). 없으면 empty. */
    @Query("SELECT MIN(r.occurredAt) FROM DeadLetterRecord r WHERE r.status IN ('OPEN', 'ACKED')")
    Optional<LocalDateTime> findOldestUnresolvedOccurredAt();

    /** age 임계값을 넘긴 미결 건 (경보 대상). */
    @Query("SELECT r FROM DeadLetterRecord r WHERE r.status IN ('OPEN', 'ACKED') "
            + "AND r.occurredAt < :threshold ORDER BY r.occurredAt ASC")
    List<DeadLetterRecord> findStaleUnresolved(@Param("threshold") LocalDateTime threshold, Pageable pageable);

    /**
     * 종결 건 정리 대상. <b>{@code OPEN}/{@code ACKED} 는 대상이 아니다</b> —
     * 장기 미결은 용량 문제가 아니라 운영 SLA 문제이고, 지우면 그 사실이 사라진다(§2.6-E).
     */
    @Query("SELECT r FROM DeadLetterRecord r WHERE r.status = 'DISCARDED' "
            + "AND r.discardedAt < :threshold ORDER BY r.discardedAt ASC")
    List<DeadLetterRecord> findPurgeable(@Param("threshold") LocalDateTime threshold, Pageable pageable);
}

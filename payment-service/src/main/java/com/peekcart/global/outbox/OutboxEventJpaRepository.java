package com.peekcart.global.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, Long> {

    // DB-per-service(구현 ② PR2): 자기 스키마의 outbox_events 만 보이므로 allowlist 없이 자기 PENDING 전체 조회.
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = 'PENDING' ORDER BY o.createdAt ASC")
    List<OutboxEvent> findPendingEvents(Pageable pageable);

    long countByStatus(OutboxEventStatus status);

    /**
     * retention cleanup 배치 삭제 (ADR-0012 D5 · 구현 ② PR3).
     * <b>PUBLISHED 상태 + {@code published_at < cutoff}</b> 인 행만 최대 {@code limit} 건 삭제한다.
     * PENDING·FAILED 는 status 조건으로, {@code published_at IS NULL} 은 {@code < cutoff} 비교(NULL 미매치)로
     * 자연히 보존된다(미발행/실패 유실 금지). 자기 트랜잭션으로 각 batch 가 독립 커밋된다.
     *
     * @return 삭제된 행 수
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM outbox_events WHERE status = 'PUBLISHED' AND published_at < :cutoff LIMIT :limit",
            nativeQuery = true)
    int deletePublishedBatchOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}

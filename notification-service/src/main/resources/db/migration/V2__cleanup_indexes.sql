-- 구현 ② PR3: retention/cleanup 인프라 (ADR-0012 D5)
-- processed_events: processed_at < cutoff 배치 삭제 지원. (notification 은 outbox 미소유)
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);

-- ShedLock 테이블 — processed cleanup 스케줄러(@SchedulerLock) 도입으로 신설.
-- notification 은 소비 전용이라 V1 시점엔 스케줄러가 없어 미보유였음.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

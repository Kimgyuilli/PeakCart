-- 구현 ② PR3: retention/cleanup 배치 삭제 기준 컬럼 인덱스 (ADR-0012 D5)
-- processed_events: processed_at < cutoff 배치 삭제 지원.
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
-- outbox_events: status = PUBLISHED AND published_at < cutoff 배치 삭제 지원.
CREATE INDEX idx_outbox_published ON outbox_events (status, published_at);

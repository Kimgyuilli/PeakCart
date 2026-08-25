-- 구현 ④-c-1b: 예약 원장의 환불 종결 표면 + 기존 감지 건 backfill (ADR-0018 D1/D4)
--
-- compensated_at 은 "결제됐는데 재고가 확정되지 않았음을 감지했다"는 marker 이지 종결 표시가 아니다
-- (ADR-0018 C1 ①/D4). 환불이 실제로 어떻게 끝났는지는 별도 컬럼에 남긴다 — 감지와 종결을 한 컬럼에
-- 섞으면 "감지했으나 환불 실패" 와 "환불 완료" 를 구분할 수 없다.
--
-- 멱등 근거는 order-service V5 와 동일하다 — producer DB 안의 NOT EXISTS 조건만이
-- "재실행 시 추가 발행 0" 을 보장한다(Payment 의 UNIQUE 는 DB 경계를 넘지 못한다, ADR-0018 D1).

ALTER TABLE stock_reservations
    ADD COLUMN refund_result       VARCHAR(20) NULL COMMENT '환불 결과 (SUCCEEDED / FAILED). 미회신이면 NULL',
    ADD COLUMN refund_resolved_at  DATETIME(6) NULL COMMENT '환불 결과 회신 반영 시각',
    ADD COLUMN refund_failure_code VARCHAR(60) NULL COMMENT '환불 영구 실패 사유 코드 (FAILED 일 때만)';

-- backfill 1단계 (2단계 분리 근거는 order V5 주석 참고 — UUID() 를 두 자리에 같은 값으로 넣기 위함)
INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, event_id,
                           payload, status, retry_count, created_at)
SELECT 'PRODUCT',
       CAST(sr.order_id AS CHAR),
       'stock.compensation.requested',
       UUID(),
       '{}',
       'BACKFILL',
       0,
       NOW(6)
  FROM stock_reservations sr
 WHERE sr.compensated_at IS NOT NULL
   AND NOT EXISTS (SELECT 1
                     FROM outbox_events oe
                    WHERE oe.aggregate_type = 'PRODUCT'
                      AND oe.aggregate_id = CAST(sr.order_id AS CHAR)
                      AND oe.event_type = 'stock.compensation.requested');

-- backfill 2단계: envelope 조립 + 'BACKFILL' → 'PENDING' 전환(한 UPDATE 에서 원자적으로). detectedAt 은 감지 marker(compensated_at) 그대로 — 런타임 발행도
-- 같은 값을 싣는다(StockReservationService.compensatePaidButUnreserved).
UPDATE outbox_events oe
  JOIN stock_reservations sr
    ON sr.order_id = CAST(oe.aggregate_id AS UNSIGNED)
   SET oe.status = 'PENDING',
       oe.payload = JSON_OBJECT(
           'eventId', oe.event_id,
           'eventType', 'stock.compensation.requested',
           'timestamp', DATE_FORMAT(oe.created_at, '%Y-%m-%dT%H:%i:%s.%f'),
           'payload', JSON_OBJECT(
               'orderId', sr.order_id,
               'reason', 'PAID_BUT_UNRESERVED',
               'detectedAt', DATE_FORMAT(sr.compensated_at, '%Y-%m-%dT%H:%i:%s.%f')),
           'schemaVersion', 1)
 WHERE oe.event_type = 'stock.compensation.requested'
   AND oe.status = 'BACKFILL';

-- 회신 소비가 orderId 로 예약 원장을 찾는다 — order_id 는 이미 UNIQUE 라 별도 인덱스 불필요.
-- backfill NOT EXISTS 조회 지원 인덱스만 추가한다.
CREATE INDEX idx_outbox_aggregate_type_id ON outbox_events (aggregate_type, aggregate_id, event_type);

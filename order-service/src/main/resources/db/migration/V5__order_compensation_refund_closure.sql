-- 구현 ④-c-1b: 주문 보상 원장의 종결 표면 + 기존 미해소 건 backfill (ADR-0018 D1/D4)
--
-- 두 가지를 한 버전에 담는다.
--   (1) 종결 컬럼 — RESOLVED("환불 완료")와 REFUND_FAILED("닫혔지만 해결되지 않음")를 구분하려면
--       실패 사유를 담을 자리가 필요하다. 실패를 RESOLVED 로 닫으면 그 원장은 거짓말을 한다(D4).
--   (2) backfill — ④-a 배포 이후 쌓인 status='OPEN' 행은 감지만 있고 요청 이벤트가 없다.
--       요청 발행 코드가 배포돼도 그 행들은 이미 소비가 끝나 재소비되지 않으므로(processed_events),
--       마이그레이션이 1회 발행해 주지 않으면 영구 미결로 남는다.
--
-- 멱등 근거: NOT EXISTS (aggregate_type + aggregate_id + event_type). Payment 의
--   payment_refunds.order_id UNIQUE 는 Payment DB 의 실행 fence 일 뿐 DB-per-service 경계를 넘지
--   못하므로, "재실행 시 추가 발행 0" 은 producer DB 안의 이 조건으로만 성립한다(ADR-0018 D1).

ALTER TABLE order_compensations
    ADD COLUMN failure_code VARCHAR(60) NULL COMMENT '환불 영구 실패 사유 코드 (REFUND_FAILED 일 때만)';

-- ---------------------------------------------------------------------------
-- backfill 1단계: 요청 Outbox 행 생성 (payload 는 2단계에서 채운다)
--
-- eventId 를 두 자리(outbox_events.event_id · payload 안의 eventId)에 넣어야 하는데 UUID() 를 한
-- SELECT 에서 두 번 부르면 서로 다른 값이 나온다. 파생 테이블은 옵티마이저가 머지해 같은 문제가
-- 재발할 수 있으므로, 컬럼에 먼저 확정한 뒤 그 값을 읽어 payload 를 만든다.
--
-- 1단계가 'PENDING' 이 아니라 **'BACKFILL'** 로 넣는 이유: 'PENDING' 은 poller 의 발행 대상 상태다
-- (`WHERE o.status = 'PENDING'`). 롤링 배포 중 살아있는 구 인스턴스의 poller 가 두 문장 사이에서
-- 이 행을 집으면 **필수 필드가 하나도 없는 payload('{}') 를 발행하고 PUBLISHED 로 봉인**해 요청이
-- 영구 유실된다. 어떤 조회도 이 값을 읽지 않으므로(전부 status 문자열로 필터) 중간 상태는 보이지 않는다.
-- 2단계가 payload 조립과 'PENDING' 전환을 **한 UPDATE 에서** 수행해, 발행 가능해지는 시점과 payload 가
-- 완성되는 시점이 같아진다. 2단계 전에 실패해도 재실행이 안전하다 — 1단계는 NOT EXISTS 로 건너뛰고
-- 2단계가 남은 'BACKFILL' 행을 이어 채운다.
-- ---------------------------------------------------------------------------
INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, event_id,
                           payload, status, retry_count, created_at)
SELECT 'ORDER',
       CAST(oc.order_id AS CHAR),
       'order.compensation.requested',
       UUID(),
       '{}',
       'BACKFILL',
       0,
       NOW(6)
  FROM order_compensations oc
 WHERE oc.status = 'OPEN'
   AND oc.reason = 'PAID_BUT_CANCELLED'
   AND NOT EXISTS (SELECT 1
                     FROM outbox_events oe
                    WHERE oe.aggregate_type = 'ORDER'
                      AND oe.aggregate_id = CAST(oc.order_id AS CHAR)
                      AND oe.event_type = 'order.compensation.requested');

-- backfill 2단계: envelope 조립 + 'BACKFILL' → 'PENDING' 전환(한 UPDATE 에서 원자적으로). eventId 는 1단계가 확정한 event_id 를 그대로 쓴다.
UPDATE outbox_events oe
  JOIN order_compensations oc
    ON oc.order_id = CAST(oe.aggregate_id AS UNSIGNED)
   AND oc.reason = 'PAID_BUT_CANCELLED'
   SET oe.status = 'PENDING',
       oe.payload = JSON_OBJECT(
           'eventId', oe.event_id,
           'eventType', 'order.compensation.requested',
           'timestamp', DATE_FORMAT(oe.created_at, '%Y-%m-%dT%H:%i:%s.%f'),
           'payload', JSON_OBJECT(
               'orderId', oc.order_id,
               'reason', 'PAID_BUT_CANCELLED',
               'detectedAt', DATE_FORMAT(oc.detected_at, '%Y-%m-%dT%H:%i:%s.%f')),
           'schemaVersion', 1)
 WHERE oe.event_type = 'order.compensation.requested'
   AND oe.status = 'BACKFILL';

-- backfill NOT EXISTS 조회 지원. 런타임 발행은 이 조회를 하지 않으므로 backfill 전용이지만,
-- (aggregate_type, aggregate_id) 조회는 운영 추적에서도 반복된다.
CREATE INDEX idx_outbox_aggregate_type_id ON outbox_events (aggregate_type, aggregate_id, event_type);

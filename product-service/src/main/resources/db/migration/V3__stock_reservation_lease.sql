-- 구현 ④-a: 재고 예약 lease (계획 P4·P13, ADR-0012 D3)
--
-- expires_at: 예약 유효기간. Product 가 부여하고 stock.reservation.result 로 Order/Payment 에 공유한다.
--             고정 TTL(reserved_at + N) 을 sweeper 가 독자 판정하면, 수명 상한이 없는 주문(예약 확정 후
--             결제 미시작 PENDING)의 재고를 살아있는 채로 회수해 oversell 이 된다 — 만료 시각을 saga
--             참여자가 공유하고 Payment 가 만료 후 승인을 거부하는 것이 이 컬럼의 존재 이유다.
--             NULL = lease 미부여(기존 행) → sweeper 회수 대상에서 제외(안전측).
ALTER TABLE stock_reservations ADD COLUMN expires_at DATETIME(6) NULL;

-- sweeper 조회(status = 'RESERVED' AND expires_at < cutoff) 지원.
-- 계획 P13 은 (status, reserved_at) 을 지목했으나 sweeper 판정 기준이 고정 TTL 에서 lease 로 바뀌었으므로
-- 실제 조회 컬럼인 expires_at 에 건다(계획 §2.3-A 결정의 파생).
CREATE INDEX idx_stock_reservations_lease ON stock_reservations (status, expires_at);

-- backfill (GW-2 #3): 기존 RESERVED 원장은 expires_at 이 NULL 이라 sweeper 에서 영구 제외된다.
-- Order 측 backfill 과 같은 기준(+30m)을 쓰므로 "Order 취소 먼저, sweeper 는 +유예 후" 순서 불변식이
-- legacy 행에서도 유지된다.
UPDATE stock_reservations
   SET expires_at = TIMESTAMPADD(MINUTE, 30, reserved_at)
 WHERE status = 'RESERVED'
   AND reserved_at IS NOT NULL
   AND expires_at IS NULL;

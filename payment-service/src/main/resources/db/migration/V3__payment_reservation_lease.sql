-- 구현 ④-a: 결제 승인 lease 게이트 (계획 P4, ADR-0012 D3)
--
-- reservation_expires_at: stock.reservation.result 로 공유받은 예약 lease 만료 시각.
--                         이 시각 이후의 승인을 거부해야 Product sweeper 의 재고 회수와 결제 승인이
--                         동시에 성립하는 oversell 구간이 생기지 않는다.
--                         NULL = lease 미수신(구 메시지) → 기존 동작 유지(만료 판정 없음).
ALTER TABLE payments ADD COLUMN reservation_expires_at DATETIME(6) NULL;

-- backfill (GW-2 #3): 기존 ready_for_payment 결제는 lease 가 NULL 이라 만료 검사 없이 계속 승인된다.
-- Order/Product 와 같은 기준(+30m)으로 소급 부여해 legacy 건도 유한한 승인 창을 갖게 한다.
UPDATE payments
   SET reservation_expires_at = TIMESTAMPADD(MINUTE, 30, created_at)
 WHERE status = 'PENDING'
   AND ready_for_payment = 1
   AND reservation_expires_at IS NULL;

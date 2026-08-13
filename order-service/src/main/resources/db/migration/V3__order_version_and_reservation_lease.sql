-- 구현 ④-a: 주문 상태 전이 낙관 락(L-013) + 재고 예약 lease 공유 (계획 P2·P4, ADR-0012 D3/D4)
--
-- version: payment.completed 소비 ↔ 타임아웃 취소의 동시 적용에서 lost update 를 차단한다.
--          실측(계획 §5 P1): @Version 부재 시 취소 선커밋 → PAYMENT_COMPLETED, 결제 선커밋 → CANCELLED 로
--          나중 커밋이 앞 커밋을 덮었다. 상태 전이 가드는 각 트랜잭션 스냅샷 기준이라 이를 막지 못한다.
ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- reservation_expires_at: Product 가 발행한 예약 lease 만료 시각(stock.reservation.result).
--                         Order 는 이 시각을 근거로 자기 주문을 먼저 취소하고, Product sweeper 는
--                         유예 이후에만 회수한다(안전망). NULL = lease 미수신(구 메시지) → 만료 판정 제외.
ALTER TABLE orders ADD COLUMN reservation_expires_at DATETIME(6) NULL;

-- 만료 lease 조회(status = PENDING AND reservation_expires_at < now) 지원.
CREATE INDEX idx_orders_reservation_expiry ON orders (status, reservation_expires_at);

-- backfill (GW-2 #3): 배포 시점에 이미 예약이 확정된 채 결제를 시작하지 않은 PENDING 주문은
-- lease 가 NULL 이라 신규 만료 잡에서도 제외되어 수명 상한이 없는 상태로 남는다(계획 P3 가 닫으려던
-- 갭이 legacy 행에 그대로 존속). 기본 TTL(app.reservation.lease.ttl=30m)과 같은 기준으로 소급 부여한다.
-- 이미 만료 시각이 과거인 행은 다음 잡 주기에 정상 취소된다(의도된 동작).
UPDATE orders
   SET reservation_expires_at = TIMESTAMPADD(MINUTE, 30, reservation_confirmed_at)
 WHERE status = 'PENDING'
   AND reservation_confirmed_at IS NOT NULL
   AND reservation_expires_at IS NULL;

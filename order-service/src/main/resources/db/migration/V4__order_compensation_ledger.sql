-- 구현 ④-a: 주문 보상 원장 (GW-2 리뷰 #2)
--
-- 배경: 취소된 주문에 payment.completed 가 도착하는 경로(낙관 락 경쟁에서 취소가 이긴 경우 등)는
--       ORD-003 으로 던져 DLQ 로 보내면 영구 실패라 무의미하고, 알림만 남기면 두 가지가 무너진다.
--       (1) order-service 의 SlackPort 는 배포 구성상 no-op 이라 알림이 실제로 나가지 않는다.
--       (2) processed_events 는 정상 커밋되므로 이벤트가 '처리 완료'로 봉인돼 후속 환불 구현(P8)이
--           같은 이벤트를 재소비할 수 없다.
--       → 소비와 같은 트랜잭션에서 '보상 필요'를 영속 원장으로 남긴다. P8(환불 요청 경로)은 이 원장을
--         입력으로 소비한다. 알림은 부가 신호일 뿐 종료 상태의 근거가 아니다.
CREATE TABLE order_compensations (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    order_id    BIGINT       NOT NULL,
    reason      VARCHAR(40)  NOT NULL,          -- PAID_BUT_CANCELLED 등
    status      VARCHAR(20)  NOT NULL,          -- OPEN / RESOLVED (해소는 P8 소관)
    detail      VARCHAR(500) NULL,
    detected_at DATETIME(6)  NOT NULL,
    resolved_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    -- (order_id, reason) 유니크 = 멱등. DLQ 재발행/재소비로 같은 사유가 두 번 와도 원장은 1행.
    CONSTRAINT uk_order_compensations_order_reason UNIQUE (order_id, reason)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 미해소 건 조회(P8 입력) 지원.
CREATE INDEX idx_order_compensations_status ON order_compensations (status, detected_at);

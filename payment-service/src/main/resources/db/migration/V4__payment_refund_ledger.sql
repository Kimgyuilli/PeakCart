-- 구현 ④-c-1a: 환불 원장 (계획 P1·P2, ADR-0018 D2/D3)
--
-- [순서 주의] payments.user_id NULL 가드를 **모든 DDL 앞**에 둔다. MySQL DDL 은 롤백되지 않으므로
-- 테이블을 먼저 만들고 뒤에서 실패하면, 데이터를 정리해 재배포해도 V4 가 처음부터 재실행되며
-- 'table already exists' 로 다시 실패한다.

-- payments.user_id 처분 (계획 P2 · ADR-0018 D1).
-- payment.refunded.userId 는 Notification 이 payload 에서 직접 읽으므로 필수인데, payments.user_id 는
-- 레거시로 nullable 이었다. NULL 이 남은 채 MODIFY 하면 sql_mode 에 따라 0 으로 조용히 치환될 수 있다
-- — 그러면 존재하지 않는 사용자에게 환불 알림이 가고 원인을 추적할 수 없다. 그래서 먼저 세어 보고,
-- 0 이 아니면 마이그레이션 자체를 실패시켜 배포를 막는다(운영이 데이터를 정리한 뒤 재배포).
SET @null_user_id_count = (SELECT COUNT(*) FROM payments WHERE user_id IS NULL);
SET @guard = IF(@null_user_id_count = 0,
                'SELECT 1',
                'SELECT * FROM `BLOCKED__payments_user_id_has_nulls__see_ADR_0018_D1`');
PREPARE guard_stmt FROM @guard;
EXECUTE guard_stmt;
DEALLOCATE PREPARE guard_stmt;

ALTER TABLE payments MODIFY COLUMN user_id BIGINT NOT NULL;

-- 환불 원장. 이 테이블은 두 가지를 동시에 한다.
--   1) 환불의 진행 상태를 소유한다 (payments.status 는 종결 REFUNDED 만 갖는다)
--   2) order_id UNIQUE 로 "동일 논리 환불 1건" fence 를 만든다
--
-- fence 가 payments.@Version 이나 조회-후-삽입이 아니라 UNIQUE 인 이유: 감지 지점이 3곳이고
-- 서로 다른 eventId 로 오기 때문에 processed_events(event_id, consumer_group) 로는 중복을 막을 수
-- 없다(ADR-0018 C3). 동시 두 진입점이 같은 스냅샷을 읽는 창은 조회로 막히지 않는다.
CREATE TABLE payment_refunds (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    order_id          BIGINT       NOT NULL,
    payment_key       VARCHAR(255) NOT NULL,
    user_id           BIGINT       NOT NULL,
    amount            BIGINT       NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    generation        BIGINT       NOT NULL DEFAULT 0,
    claimed_at        DATETIME(6)  NULL,
    last_error        VARCHAR(500) NULL,
    pg_response       TEXT         NULL,
    failure_code      VARCHAR(100) NULL,
    requested_at      DATETIME(6)  NOT NULL,
    resolved_at       DATETIME(6)  NULL,
    resolved_by       VARCHAR(100) NULL,
    resolution_reason VARCHAR(500) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_refunds_order UNIQUE (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 컬럼 의도:
--   payment_key : payments.payment_key(255)와 동일 폭. 좁으면 INSERT IGNORE 가 잘린 키를 조용히
--                 넣고 이후 취소·조회가 잘못된 키로 나간다.
--   status      : REQUESTED / CLAIMED / SUCCEEDED / FAILED / UNRESOLVED
--   generation  : fencing token. claim 마다 증가해 만료된 owner 의 뒤늦은 확정을 무효화한다.
--   claimed_at  : claim 시각 = lease 기준
--   failure_code: 실패 회신 payload 의 필수 필드(ADR-0018 D1)
--   resolved_by / resolution_reason : 수동 종결 감사(둘 다 있어야 종결 허용)

-- dispatcher 후보(REQUESTED) + reconciliation 후보(lease 만료 CLAIMED · UNRESOLVED) 조회 지원.
CREATE INDEX idx_payment_refunds_status_claimed ON payment_refunds (status, claimed_at);

-- backlog 건수 / 최장 age 게이지(ADR-0018 D6) 지원.
CREATE INDEX idx_payment_refunds_status_requested ON payment_refunds (status, requested_at);

-- reconciliation 순회(claimed_at 오름차순 — 처리한 행이 뒤로 밀려 starvation 을 막는다) 지원.
CREATE INDEX idx_payment_refunds_claimed ON payment_refunds (claimed_at);

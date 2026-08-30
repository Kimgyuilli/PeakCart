-- 구현 ⑥: 주문 내역 커서 페이지네이션 (ordered_at, id) 정렬 키 지원 인덱스.
--
-- id 를 명시하지 않는다 — InnoDB 세컨더리 인덱스는 PK 를 암묵 부착하므로
-- (user_id, ordered_at) 이 곧 (user_id, ordered_at, id) 이고, tie-break 도 이 인덱스로 처리된다.
-- 이 전제는 OrderCursorQueryPlanTest 가 EXPLAIN 으로 검증한다.
--
-- ALGORITHM/LOCK 을 명시해 온라인 DDL 이 불가능한 경우 조용히 테이블을 잠그는 대신
-- 마이그레이션이 실패하도록 만든다. 앱 Pod 시작 시 Flyway 가 도므로 잠금은 곧 롤아웃 정체다.
-- CREATE INDEX 에서는 두 옵션 사이에 쉼표를 쓰지 않는다 — 쉼표 형식은 ALTER TABLE 전용이라
-- ERROR 1064 가 된다(MySQL 8.0.46 실측).
--
-- 기존 idx_orders_user_id_status 는 유지한다 — 상태 필터 조회를 이 인덱스가 대체하지 않는다.
CREATE INDEX idx_orders_user_id_ordered_at ON orders (user_id, ordered_at)
    ALGORITHM=INPLACE LOCK=NONE;

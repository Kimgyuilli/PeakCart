-- ============================================================
-- Phase 3 Task 3-4 부하 테스트 시드 데이터
--
-- Flyway 밖 독립 스크립트 — 본 앱 migration 과 섞지 않음.
-- 적용: kubectl exec -n peekcart <mysql-pod> -- mysql -upeekcart -p<pw> peekcart < seed.sql
--       또는 loadgen VM 에서 원격 접속으로 실행.
--
-- 재실행 가능: 기존 시드 데이터를 삭제 후 재삽입 (주문/결제 등 하위 테이블 FK 고려).
-- 본 스크립트는 로드 테스트 전용이며 스키마를 변경하지 않는다.
--
-- 비밀번호 해시는 "LoadTest123!" 의 BCrypt(cost=10) 결과.
-- JMeter 시나리오에서 로그인 시 동일 평문 사용.
-- ============================================================

SET @password_hash = '$2a$10$uyo/cG3tOHyV36gx4aaH6OPKEonaX/ytclNITv/cJhopxacnjS5Qq';
SET @now = NOW(6);
SET SESSION cte_max_recursion_depth = 2000;  -- 기본 1000 → 1100 users 생성 허용

-- ------------------------------------------------------------
-- 1. 정리 (하위 → 상위 순서로 FK 위반 회피)
-- ------------------------------------------------------------
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE notifications;
TRUNCATE TABLE webhook_logs;
TRUNCATE TABLE payments;
TRUNCATE TABLE order_items;
TRUNCATE TABLE orders;
TRUNCATE TABLE cart_items;
TRUNCATE TABLE carts;
TRUNCATE TABLE inventories;
TRUNCATE TABLE products;
TRUNCATE TABLE categories;
TRUNCATE TABLE addresses;
TRUNCATE TABLE refresh_tokens;
TRUNCATE TABLE users;

SET FOREIGN_KEY_CHECKS = 1;

-- ------------------------------------------------------------
-- 2. 사용자 (admin 1 + loaduser 1100)
--    loaduser0001 ~ loaduser1100  — JMeter 1,000 VUser + 100 여유분
-- ------------------------------------------------------------
INSERT INTO users (email, password_hash, name, role, created_at, updated_at)
VALUES ('admin@peekcart.test', @password_hash, 'Admin', 'ADMIN', @now, @now);

INSERT INTO users (email, password_hash, name, role, created_at, updated_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 1100
)
SELECT
    CONCAT('loaduser', LPAD(n, 4, '0'), '@peekcart.test'),
    @password_hash,
    CONCAT('LoadUser', LPAD(n, 4, '0')),
    'USER',
    @now,
    @now
FROM seq;

-- 모든 loaduser 에 기본 배송지 1건
INSERT INTO addresses (user_id, receiver_name, phone, zipcode, address, is_default)
SELECT id, name, '010-0000-0000', '06236', 'Seoul Gangnam Loadtest Addr', TRUE
FROM users WHERE role = 'USER';

-- ------------------------------------------------------------
-- 3. 카테고리 (5개, 평면 구조)
-- ------------------------------------------------------------
INSERT INTO categories (name, parent_id) VALUES
    ('Electronics', NULL),
    ('Fashion', NULL),
    ('Home', NULL),
    ('Beauty', NULL),
    ('Sports', NULL);

-- ------------------------------------------------------------
-- 4. 상품 1,010건
--    1~1,000: 일반 카탈로그 (시나리오 1 상품 조회 TPS 측정 대상)
--              stock = 10000 (풍부 — 조회만, 주문 부산물 대비 여유)
--    1,001~1,010: 경합 타깃 (시나리오 2 오버셀링 검증 대상)
--              stock = 100 → 총 1,000 재고, 1,000 VUser 동시 주문 시 정합성 검증
-- ------------------------------------------------------------
INSERT INTO products (category_id, name, description, price, image_url, status, created_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 1000
)
SELECT
    ((n - 1) % 5) + 1,                                       -- 카테고리 균등 분배
    CONCAT('Product ', LPAD(n, 4, '0')),
    CONCAT('Load test catalog item #', n),
    1000 + (n % 100) * 100,                                  -- 1,000 ~ 10,900
    CONCAT('https://example.test/img/', n, '.jpg'),
    'ON_SALE',
    @now
FROM seq;

-- 경합 타깃 10건 — 명시 ID (id 1001~1010) 로 삽입.
-- 이유: innodb_autoinc_lock_mode=2 (MySQL 8 기본) 에서 INSERT...SELECT 는
--       auto_increment 를 블록 단위로 할당하여 ID 간극이 발생. JMeter 시나리오가
--       고정 범위 [1001..1010] 을 참조하므로 ID 안정성이 필수.
ALTER TABLE products AUTO_INCREMENT = 1001;

INSERT INTO products (id, category_id, name, description, price, image_url, status, created_at) VALUES
    (1001, 1, 'Contention Target 1',  'Scarcity item for concurrent order scenario', 5000, 'https://example.test/img/contention-1.jpg',  'ON_SALE', @now),
    (1002, 1, 'Contention Target 2',  'Scarcity item for concurrent order scenario', 5000, 'https://example.test/img/contention-2.jpg',  'ON_SALE', @now),
    (1003, 1, 'Contention Target 3',  'Scarcity item for concurrent order scenario', 5000, 'https://example.test/img/contention-3.jpg',  'ON_SALE', @now),
    (1004, 1, 'Contention Target 4',  'Scarcity item for concurrent order scenario', 5000, 'https://example.test/img/contention-4.jpg',  'ON_SALE', @now),
    (1005, 1, 'Contention Target 5',  'Scarcity item for concurrent order scenario', 5000, 'https://example.test/img/contention-5.jpg',  'ON_SALE', @now),
    (1006, 1, 'Contention Target 6',  'Scarcity item for concurrent order scenario', 5000, 'https://example.test/img/contention-6.jpg',  'ON_SALE', @now),
    (1007, 1, 'Contention Target 7',  'Scarcity item for concurrent order scenario', 5000, 'https://example.test/img/contention-7.jpg',  'ON_SALE', @now),
    (1008, 1, 'Contention Target 8',  'Scarcity item for concurrent order scenario', 5000, 'https://example.test/img/contention-8.jpg',  'ON_SALE', @now),
    (1009, 1, 'Contention Target 9',  'Scarcity item for concurrent order scenario', 5000, 'https://example.test/img/contention-9.jpg',  'ON_SALE', @now),
    (1010, 1, 'Contention Target 10', 'Scarcity item for concurrent order scenario', 5000, 'https://example.test/img/contention-10.jpg', 'ON_SALE', @now);

-- ------------------------------------------------------------
-- 5. 재고
--    일반 상품: 10,000
--    경합 상품 (id 1001~1010): 100
-- ------------------------------------------------------------
INSERT INTO inventories (product_id, stock, version, updated_at)
SELECT
    id,
    CASE WHEN name LIKE 'Contention Target%' THEN 100 ELSE 10000 END,
    0,
    @now
FROM products;

-- ------------------------------------------------------------
-- 6. 검증 카운트 (적용 후 즉시 확인용 주석)
-- ------------------------------------------------------------
-- SELECT COUNT(*) FROM users;         -- 1101  (admin 1 + loaduser 1100)
-- SELECT COUNT(*) FROM categories;    -- 5
-- SELECT COUNT(*) FROM products;      -- 1010
-- SELECT COUNT(*) FROM inventories;   -- 1010
-- SELECT SUM(stock) FROM inventories WHERE product_id > 1000;  -- 1000 (경합 재고 총합)

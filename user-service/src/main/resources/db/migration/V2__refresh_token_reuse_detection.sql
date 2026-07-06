-- ============================================================
-- user-service — Refresh Token Reuse Detection (구현 ③ PR2, ADR-0013 D4)
-- 삭제 기반 rotation → family_id/status 상태전이 모델로 전환.
-- 그린필드(보존 prod 이력 없음) — 기존 refresh_tokens row 전량 무효화(재로그인 요구).
-- 평문 token 컬럼은 token_hash(SHA-256 hex, TokenHasher)로 대체하고 드롭한다.
-- 동일 스키마 내 fk_refresh_tokens_user 는 유지.
-- ============================================================

-- 기존 row 전량 무효화 (평문 UUID → token_hash backfill 미채택, 재로그인)
DELETE FROM refresh_tokens;

-- 평문 token 컬럼 + 그 unique key 드롭 (token_hash 로 대체)
ALTER TABLE refresh_tokens
    DROP KEY uk_refresh_tokens_token,
    DROP COLUMN token;

-- 상태전이 모델 컬럼 추가
ALTER TABLE refresh_tokens
    ADD COLUMN family_id            VARCHAR(36) NOT NULL,
    ADD COLUMN token_hash           VARCHAR(64) NOT NULL,
    ADD COLUMN status               VARCHAR(20) NOT NULL,
    ADD COLUMN rotated_at           DATETIME(6) NULL,
    ADD COLUMN grace_until          DATETIME(6) NULL,
    ADD COLUMN replaced_by_token_id BIGINT      NULL;

-- token_hash 중복 = 조회 모호성·reuse 오판 → unique. family 단위 조회 인덱스.
ALTER TABLE refresh_tokens
    ADD UNIQUE KEY uk_refresh_tokens_token_hash (token_hash),
    ADD KEY idx_refresh_tokens_family_id (family_id);

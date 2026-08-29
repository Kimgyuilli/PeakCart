#!/bin/bash
# E2E cold-start marker (계획 P4).
#
# **왜 .sh 인가**: 정적 .sql 로는 현재 run_id 를 주입할 수 없다. 그리고 warm datadir 에서는
# /docker-entrypoint-initdb.d 자체가 재실행되지 않으므로 "기존 marker 면 여기서 실패" 식의
# 분기는 도달하지 않는다 — 판정은 **readiness 가 stored_run_id 를 현재 run_id 와 대조**하는
# 쪽에서 이뤄진다. 이 스크립트는 그 대조에 쓸 값을 최초 1회 심는 역할만 한다.
#
# marker 는 앱 스키마가 아니라 전용 스키마에 둔다 — 앱 테이블 DDL 은 Flyway 전용이라는
# 규칙과 섞이면 안 되고, flyway_schema_history 검사와도 독립이어야 한다.
set -euo pipefail

: "${E2E_RUN_ID:?E2E_RUN_ID 가 필요하다 — cold start 판정의 입력이다}"

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS peekcart_e2e_meta
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE peekcart_e2e_meta.run_marker (
    id      TINYINT      NOT NULL PRIMARY KEY,
    run_id  VARCHAR(128) NOT NULL,
    created DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

INSERT INTO peekcart_e2e_meta.run_marker (id, run_id) VALUES (1, '${E2E_RUN_ID}');

GRANT SELECT ON peekcart_e2e_meta.* TO 'peekcart_order'@'%';
FLUSH PRIVILEGES;
SQL

echo "[e2e] run marker 기록 완료 — run_id=${E2E_RUN_ID}"

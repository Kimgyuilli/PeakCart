#!/usr/bin/env bash
# dead-letter-schema-parity-lint — 4서비스 dead_letter_records 스키마 동일성 (구현 ④-c-2a P10)
#
# 무엇을 막는가:
#   DLQ 원장은 DB-per-service(ADR-0012 D1) 때문에 order/product/payment/notification 각자의 DB 에
#   같은 테이블을 둔다. 한 서비스에서만 컬럼을 고치면 runbook 의 조회 절차가 그 서비스에서만 깨지고,
#   깨진 사실은 운영자가 그 DB 를 볼 때까지 드러나지 않는다.
#
# 왜 테스트가 아니라 lint 인가:
#   테이블이 4개 모듈에 흩어져 있어 한 모듈의 테스트가 다른 모듈의 마이그레이션을 볼 수 없다.
#   비교 대상이 소스 파일이므로 정적 검사가 맞다.
#
# 무엇을 비교하는가:
#   (1) CREATE TABLE dead_letter_records (...) 본문과 인덱스 정의. 주석·공백은 무시한다.
#   (2) ALTER TABLE dead_letter_records 후속 마이그레이션 (④-c-2b-1 P1 replay 축) — glob 을 넓히지 않으면
#       신규 파일이 검사에 걸리지 않아 4벌이 갈라져도 아무 것도 실패하지 않는다.
#   (3) global/deadletter java 파일 (④-c-2b-1 P1-b) — 4서비스에 byte 동일 복제이며, 한 벌만 고치는 실수를
#       스키마와 같은 이유로 막아야 한다. 원장 로직이 서비스마다 달라지면 runbook 절차가 한 곳에서만 깨진다.
#
# 사용: bash scripts/dead-letter-schema-parity-lint.sh [--self-test]
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

LINT_PY="$(mktemp -t dead-letter-schema-parity-lint.XXXXXX.py)"
trap 'rm -f "$LINT_PY"' EXIT

cat > "$LINT_PY" <<'PYEOF'
import glob
import hashlib
import os
import re
import sys

root = sys.argv[1]
services = ["order-service", "product-service", "payment-service", "notification-service"]

violations = []


def normalize(sql):
    """주석·공백을 제거해 의미 있는 DDL 만 남긴다."""
    out = []
    for line in sql.splitlines():
        line = re.sub(r"--.*$", "", line).strip()
        if line:
            out.append(re.sub(r"\s+", " ", line))
    return "\n".join(out)


def extract(path):
    with open(path, encoding="utf-8") as f:
        sql = f.read()
    body = normalize(sql)
    if "CREATE TABLE dead_letter_records" not in body:
        return None
    return body


fingerprints = {}
for service in services:
    matches = glob.glob(os.path.join(root, service, "src/main/resources/db/migration/V*__dead_letter_records.sql"))
    if len(matches) != 1:
        violations.append(
            "[DLQ-PARITY-001] %s: dead_letter_records 마이그레이션이 %d개다 (정확히 1개여야 한다)"
            % (service, len(matches)))
        continue
    body = extract(matches[0])
    if body is None:
        violations.append("[DLQ-PARITY-002] %s: %s 에 CREATE TABLE dead_letter_records 가 없다"
                          % (service, os.path.basename(matches[0])))
        continue
    fingerprints[service] = (os.path.basename(matches[0]), hashlib.sha256(body.encode()).hexdigest(), body)

if len(fingerprints) == len(services):
    digests = {d for _, d, _ in fingerprints.values()}
    if len(digests) != 1:
        violations.append("[DLQ-PARITY-003] 4서비스의 dead_letter_records 스키마가 다르다:")
        for service, (fname, digest, _) in sorted(fingerprints.items()):
            violations.append("    %-22s %s  %s" % (service, digest[:12], fname))
        # 첫 서비스를 기준으로 차이 나는 줄을 보여준다
        baseline_service = services[0]
        baseline = fingerprints[baseline_service][2].splitlines()
        for service, (_, digest, body) in sorted(fingerprints.items()):
            if service == baseline_service:
                continue
            lines = body.splitlines()
            only_here = [l for l in lines if l not in baseline]
            only_there = [l for l in baseline if l not in lines]
            if only_here or only_there:
                violations.append("    --- %s vs %s" % (service, baseline_service))
                for l in only_here:
                    violations.append("      + %s" % l)
                for l in only_there:
                    violations.append("      - %s" % l)

# UNIQUE 키가 6컬럼인지 — 여기가 무너지면 poison record 가 여러 행이 된다
for service, (fname, _, body) in sorted(fingerprints.items()):
    m = re.search(r"CONSTRAINT uk_dead_letter_records_origin UNIQUE \(([^)]*)\)", body)
    if not m:
        violations.append("[DLQ-PARITY-004] %s: uk_dead_letter_records_origin UNIQUE 제약이 없다" % service)
        continue
    columns = [c.strip() for c in m.group(1).split(",") if c.strip()]
    expected = ["cluster_id", "topic_generation", "origin_topic",
                "origin_partition", "origin_offset", "failed_consumer_group"]
    if columns != expected:
        violations.append("[DLQ-PARITY-005] %s: UNIQUE 컬럼이 계약과 다르다\n    기대: %s\n    실제: %s"
                          % (service, expected, columns))

# 식별자 6컬럼은 전부 NOT NULL — nullable 이면 MySQL UNIQUE 가 중복을 막지 못한다
for service, (fname, _, body) in sorted(fingerprints.items()):
    for column in ["cluster_id", "topic_generation", "origin_topic",
                   "origin_partition", "origin_offset", "failed_consumer_group"]:
        m = re.search(r"^%s\s+[A-Z]+(\([0-9]+\))?\s+(NOT NULL)" % re.escape(column), body, re.M)
        if not m:
            violations.append("[DLQ-PARITY-006] %s: 식별자 컬럼 %s 가 NOT NULL 이 아니다 "
                              "(nullable 이면 UNIQUE 가 중복을 막지 못한다)" % (service, column))

# --- (2) replay 축 ALTER 마이그레이션 parity (④-c-2b-1 P1) ---
alter_prints = {}
for service in services:
    matches = glob.glob(os.path.join(root, service, "src/main/resources/db/migration/V*__dead_letter_replay_axis.sql"))
    if len(matches) != 1:
        violations.append(
            "[DLQ-PARITY-007] %s: dead_letter_replay_axis 마이그레이션이 %d개다 (정확히 1개여야 한다)"
            % (service, len(matches)))
        continue
    # **바이너리로 읽어 그대로 해시한다.** text mode 로 읽으면 Python 이 universal-newline 변환을 해서
    # LF 와 CRLF 파일이 같은 해시가 된다 — "원문 바이트 동일" 을 표방하면서 개행 drift 를 통과시킨다.
    with open(matches[0], "rb") as f:
        raw_bytes = f.read()
    body = normalize(raw_bytes.decode("utf-8"))
    if "ALTER TABLE dead_letter_records" not in body:
        violations.append("[DLQ-PARITY-008] %s: %s 에 ALTER TABLE dead_letter_records 가 없다"
                          % (service, os.path.basename(matches[0])))
        continue
    # **원문 바이트로 해시한다.** 이 파일들은 한 벌을 복사해 만든 사본이므로 주석·공백 차이도 drift 다
    # (normalize 해시는 주석이 갈라져도 통과한다). CREATE TABLE 쪽은 ④-c-2a 가 정한 정규화 비교를 유지한다.
    alter_prints[service] = (os.path.basename(matches[0]), hashlib.sha256(raw_bytes).hexdigest(), body)

if len(alter_prints) == len(services):
    digests = {d for _, d, _ in alter_prints.values()}
    if len(digests) != 1:
        violations.append("[DLQ-PARITY-009] 4서비스의 replay 축 마이그레이션이 다르다 (주석·공백 포함 원문 대조):")
        for service, (fname, digest, _) in sorted(alter_prints.items()):
            violations.append("    %-22s %s  %s" % (service, digest[:12], fname))

    # replay 축 컬럼은 **전부 nullable** 이어야 한다 — NOT NULL 을 먼저 걸면 롤링 배포 중 구버전 INSERT 가 깨지고,
    # root_record_id 의 expand→backfill→contract 순서(ADR-0020 D6-3)가 성립하지 않는다.
    # 서비스마다 검사한다 — 기준 1벌만 보면 변이가 다른 서비스에 있을 때 이 검사가 새 나간다.
    for service, (_, _, body) in sorted(alter_prints.items()):
        for column in ["root_record_id", "publication_status", "outbox_event_id",
                       "last_replay_attempt_id", "last_replay_target_group", "replay_deadline",
                       "replay_policy", "resolved_at", "resolved_by", "reopened_at", "reopened_reason"]:
            m = re.search(r"ADD COLUMN %s\s+[A-Z]+(\([0-9]+\))?\s+(NOT NULL|NULL)" % re.escape(column), body)
            if not m:
                violations.append("[DLQ-PARITY-010] %s: replay 축 컬럼 %s 가 마이그레이션에 없다" % (service, column))
            elif m.group(2) != "NULL":
                violations.append("[DLQ-PARITY-011] %s: replay 축 컬럼 %s 가 nullable 이 아니다 "
                                  "(롤링 배포 중 구버전 INSERT 가 깨지고 expand→backfill 순서가 성립하지 않는다)"
                                  % (service, column))

# --- (3) global/deadletter java 복제 parity (④-c-2b-1 P1-b) ---
java_files = ["DeadLetterRecord", "DeadLetterStatus", "PublicationStatus", "DeadLetterRecorder",
              "DeadLetterRecordJpaRepository", "DeadLetterEndpoint", "DeadLetterMetrics",
              "DeadLetterMaintenanceScheduler", "DeadLetterTransitionService"]
for name in java_files:
    digests = {}
    for service in services:
        path = os.path.join(root, service, "src/main/java/com/peekcart/global/deadletter", name + ".java")
        if not os.path.exists(path):
            violations.append("[DLQ-PARITY-012] %s: %s.java 가 없다" % (service, name))
            continue
        with open(path, "rb") as f:   # 개행 drift 를 놓치지 않도록 바이너리로 읽는다
            digests[service] = hashlib.sha256(f.read()).hexdigest()
    if len(digests) == len(services) and len({d for d in digests.values()}) != 1:
        violations.append("[DLQ-PARITY-013] %s.java 가 4서비스에서 동일하지 않다:" % name)
        for service, digest in sorted(digests.items()):
            violations.append("    %-22s %s" % (service, digest[:12]))

if violations:
    print("dead-letter-schema-parity-lint 위반:")
    for v in violations:
        print("  " + v)
    sys.exit(1)

print("dead-letter-schema-parity-lint OK — 4서비스 dead_letter_records 스키마·replay 축 마이그레이션·java %d파일 동일"
      % len(java_files))
PYEOF

if [[ "${1:-}" == "--self-test" ]]; then
    TMP="$(mktemp -d)"
    trap 'rm -f "$LINT_PY"; rm -rf "$TMP"' EXIT

    seed_fixture() {
        for svc in order-service product-service payment-service notification-service; do
            mkdir -p "$TMP/$svc/src/main/resources/db/migration"
            mkdir -p "$TMP/$svc/src/main/java/com/peekcart/global/deadletter"
            cp order-service/src/main/resources/db/migration/V*__dead_letter_records.sql \
               "$TMP/$svc/src/main/resources/db/migration/V1__dead_letter_records.sql"
            cp order-service/src/main/resources/db/migration/V*__dead_letter_replay_axis.sql \
               "$TMP/$svc/src/main/resources/db/migration/V2__dead_letter_replay_axis.sql"
            cp order-service/src/main/java/com/peekcart/global/deadletter/*.java \
               "$TMP/$svc/src/main/java/com/peekcart/global/deadletter/"
        done
    }

    # self-test 1: 정상 사본 4개 → 통과
    seed_fixture
    if ! python3 "$LINT_PY" "$TMP" >/dev/null; then
        echo "self-test 1 실패: 동일 사본인데 위반으로 판정"; exit 1
    fi

    # self-test 2: 한 서비스 컬럼 변조 → 검출되어야 한다
    sed -i.bak 's/note                  VARCHAR(1000) NULL/note                  VARCHAR(500) NULL/' \
        "$TMP/product-service/src/main/resources/db/migration/V1__dead_letter_records.sql"
    if python3 "$LINT_PY" "$TMP" >/dev/null 2>&1; then
        echo "self-test 2 실패: 스키마 차이를 검출하지 못했다"; exit 1
    fi

    # self-test 3: NOT NULL 제거 → 검출되어야 한다
    seed_fixture
    sed -i.bak 's/failed_consumer_group VARCHAR(120) NOT NULL/failed_consumer_group VARCHAR(120) NULL    /' \
        "$TMP/order-service/src/main/resources/db/migration/V1__dead_letter_records.sql"
    for svc in product-service payment-service notification-service; do
        sed -i.bak 's/failed_consumer_group VARCHAR(120) NOT NULL/failed_consumer_group VARCHAR(120) NULL    /' \
            "$TMP/$svc/src/main/resources/db/migration/V1__dead_letter_records.sql"
    done
    if python3 "$LINT_PY" "$TMP" >/dev/null 2>&1; then
        echo "self-test 3 실패: 식별자 컬럼 nullable 을 검출하지 못했다"; exit 1
    fi

    # self-test 4: 마이그레이션 누락 → 검출되어야 한다
    #   **정상 fixture 에서 시작하고 위반 코드를 직접 단언한다** — self-test 3 의 변조를 이어받은 채
    #   "비정상 종료" 만 확인하면, 001 검사를 지워도 남아 있는 006 위반 때문에 계속 통과한다(false-green).
    seed_fixture
    rm "$TMP/notification-service/src/main/resources/db/migration/V1__dead_letter_records.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-001" <<<"$OUT"; then
        echo "self-test 4 실패: 마이그레이션 누락(001)을 검출하지 못했다"; exit 1
    fi

    # self-test 5: replay 축 컬럼을 NOT NULL 로 → 검출되어야 한다
    #   (order 가 아니라 **payment** 를 변조한다 — 기준 1벌만 검사하면 새 나가는 경로를 막기 위해서다)
    seed_fixture
    sed -i.bak 's/ADD COLUMN publication_status       VARCHAR(20)   NULL,/ADD COLUMN publication_status       VARCHAR(20)   NOT NULL,/' \
        "$TMP/payment-service/src/main/resources/db/migration/V2__dead_letter_replay_axis.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-011" <<<"$OUT"; then
        echo "self-test 5 실패: replay 축 컬럼 NOT NULL 을 검출하지 못했다"; exit 1
    fi

    # self-test 6: java 를 한 벌만 수정 → 검출되어야 한다
    seed_fixture
    echo "// drift" >> "$TMP/product-service/src/main/java/com/peekcart/global/deadletter/DeadLetterStatus.java"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-013" <<<"$OUT"; then
        echo "self-test 6 실패: java 복제 drift 를 검출하지 못했다"; exit 1
    fi

    # self-test 7: replay 축 마이그레이션 누락 → 검출되어야 한다
    seed_fixture
    rm "$TMP/notification-service/src/main/resources/db/migration/V2__dead_letter_replay_axis.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-007" <<<"$OUT"; then
        echo "self-test 7 실패: replay 축 마이그레이션 누락을 검출하지 못했다"; exit 1
    fi

    # self-test 8: **주석만** 갈라진 경우 → 009 로 검출되어야 한다.
    #   타입/길이 변경으로 검사하면 normalize 해시로도 잡히므로 "원문 바이트 대조" 로 바꾼 것 자체를
    #   고정하지 못한다. 주석은 normalize 가 지우므로, 이 케이스가 red 여야 byte 해시가 실제로 동작한다.
    seed_fixture
    printf '\n-- drift comment\n' >> "$TMP/product-service/src/main/resources/db/migration/V2__dead_letter_replay_axis.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-009" <<<"$OUT"; then
        echo "self-test 8 실패: 주석만 다른 사본(normalize 로는 동일)을 검출하지 못했다"; exit 1
    fi

    # self-test 8b: nullable 을 유지한 **타입/길이** drift → 009 로 검출 (011 은 nullable 만 본다)
    seed_fixture
    sed -i.bak 's/ADD COLUMN replay_policy            VARCHAR(120)  NULL,/ADD COLUMN replay_policy            VARCHAR(200)  NULL,/' \
        "$TMP/product-service/src/main/resources/db/migration/V2__dead_letter_replay_axis.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-009" <<<"$OUT"; then
        echo "self-test 8b 실패: replay 축 SQL 타입/길이 drift 를 검출하지 못했다"; exit 1
    fi

    # self-test 9: java 파일 **삭제** → 012 로 검출 (013 은 존재하는 파일끼리만 비교한다)
    seed_fixture
    rm "$TMP/payment-service/src/main/java/com/peekcart/global/deadletter/DeadLetterTransitionService.java"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-012" <<<"$OUT"; then
        echo "self-test 9 실패: java 파일 누락을 검출하지 못했다"; exit 1
    fi

    echo "dead-letter-schema-parity-lint self-test 10종 통과"
    exit 0
fi

python3 "$LINT_PY" "$(pwd)"

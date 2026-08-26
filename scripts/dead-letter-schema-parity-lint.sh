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
#   CREATE TABLE dead_letter_records (...) 본문과 인덱스 정의. 주석·공백은 무시한다.
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

if violations:
    print("dead-letter-schema-parity-lint 위반:")
    for v in violations:
        print("  " + v)
    sys.exit(1)

print("dead-letter-schema-parity-lint OK — 4서비스 dead_letter_records 스키마 동일")
PYEOF

if [[ "${1:-}" == "--self-test" ]]; then
    TMP="$(mktemp -d)"
    trap 'rm -f "$LINT_PY"; rm -rf "$TMP"' EXIT

    # self-test 1: 정상 사본 4개 → 통과
    for svc in order-service product-service payment-service notification-service; do
        mkdir -p "$TMP/$svc/src/main/resources/db/migration"
        cp order-service/src/main/resources/db/migration/V*__dead_letter_records.sql \
           "$TMP/$svc/src/main/resources/db/migration/V1__dead_letter_records.sql"
    done
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
    for svc in order-service product-service payment-service notification-service; do
        cp order-service/src/main/resources/db/migration/V*__dead_letter_records.sql \
           "$TMP/$svc/src/main/resources/db/migration/V1__dead_letter_records.sql"
    done
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
    rm "$TMP/notification-service/src/main/resources/db/migration/V1__dead_letter_records.sql"
    if python3 "$LINT_PY" "$TMP" >/dev/null 2>&1; then
        echo "self-test 4 실패: 마이그레이션 누락을 검출하지 못했다"; exit 1
    fi

    echo "dead-letter-schema-parity-lint self-test 4종 통과"
    exit 0
fi

python3 "$LINT_PY" "$(pwd)"

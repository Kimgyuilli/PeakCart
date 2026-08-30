#!/usr/bin/env bash
# observability-ssot-lint.sh — D5-V1 + D5-V2 정적 lint (ADR-0009 §Decision, ADR-0015 per-service 정정)
#
# per-service 계약(ADR-0015): 5서비스 분리 완료 후 SSOT 는 root 단일 application.yml 이 아니라
# 각 서비스 모듈의 `<svc>-service/src/main/resources/application.yml` 이며, application 태그 값은
# 단일 'peekcart' 가 아니라 서비스 자기 이름(`<svc>-service`)이다.
#
# D5-V1: SSOT 위치 위반 — 각 서비스 base application.yml 가 SSOT 인 키
#        (management.metrics.tags.application, management.endpoints.web.exposure.include)
#        가 그 서비스의 다른 프로파일 yaml 에 재선언됐는지 검출.
# D5-V2: 중복 재선언/복제 — MetricsConfig.java(peekcart-common-observability 단일 owner) 외
#        다른 클래스의 MeterRegistryCustomizer / MeterFilter, 그리고
#        management.metrics.tags.application 의 값이 서비스 자기 이름과 불일치하는지 검출.
#
# 화이트리스트 (ADR-0007 / ADR-0009 §회색지대 분류):
#   - management.endpoint.health.probes.enabled
#   - management.endpoint.health.show-details
#   - logging.level.*
#   - spring.jpa.show-sql / format_sql
# 화이트리스트 변경 시 ADR-0007/0009/0015 갱신 의무.
#
# Exit:
#   0 — 위반 0건
#   1 — 위반 1건 이상
#   2 — preflight 실패 (pyyaml 미설치 등)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VIOLATIONS=0

# pyyaml preflight (CI 는 ci.yml step 으로 설치, 로컬은 venv/pip 권장)
if ! python3 -c 'import yaml' 2>/dev/null; then
    echo "[lint] pyyaml 미설치 — \`python3 -m pip install --user pyyaml\` 필요" >&2
    exit 2
fi

# ---------- D5-V1 + D5-V2 (yaml side) — per-service ----------
# `set -euo pipefail` 하에서 inline python3 의 비-0 종료가 즉시 스크립트를 종료시켜
# Java side 검사가 누락되는 것을 막기 위해 `|| PY_RC=$?` 로 exit code 를 보존한다.
PY_RC=0
python3 - <<'PY' || PY_RC=$?
import sys, glob, os
import yaml

# 5서비스 정본 (ADR-0010/0015) — glob 결과를 정본으로 삼지 않는다.
# 한 서비스 yml 이 삭제/리네임돼 glob 이 4개로 줄어도 남은 4개로 통과하는 false-green 차단.
EXPECTED_SERVICES = [
    "notification-service", "order-service", "payment-service",
    "product-service", "user-service",
]
EXPECTED_BASES = {f"{s}/src/main/resources/application.yml" for s in EXPECTED_SERVICES}
found_bases = set(glob.glob("*-service/src/main/resources/application.yml"))
BASES = sorted(EXPECTED_BASES)

# ADR-0009 §Decision S2/S3: 각 서비스 base SSOT keys
SSOT_KEYS = [
    ("management", "metrics", "tags", "application"),
    ("management", "endpoints", "web", "exposure", "include"),
]

def get_path(d, path):
    cur = d
    for k in path:
        if not isinstance(cur, dict) or k not in cur:
            return (False, None)
        cur = cur[k]
    return (True, cur)

def load(p):
    with open(p) as f:
        return yaml.safe_load(f) or {}

violations = []

# 정본 집합과 실제 발견 집합 일치 검증 (누락/초과 false-green 차단)
if found_bases != EXPECTED_BASES:
    violations.append(
        f"[D5-V1] 서비스 base application.yml 집합 불일치:\n"
        f"  expected(5 정본): {sorted(EXPECTED_BASES)}\n"
        f"  found:            {sorted(found_bases)}\n"
        f"  missing: {sorted(EXPECTED_BASES - found_bases)} / extra: {sorted(found_bases - EXPECTED_BASES)}\n"
        f"  → ADR-0015: 5서비스 정본 yml 필수 — glob 축소 시 coverage false-green 차단.\n"
    )

for base in BASES:
    if not os.path.exists(base):
        continue                                  # 누락은 위 집합 불일치로 이미 보고 — load crash 방지
    module_dir = base.split("/", 1)[0]           # e.g. order-service
    res_dir = os.path.dirname(base)              # <svc>-service/src/main/resources
    profiles = sorted(glob.glob(os.path.join(res_dir, "application-*.yml")))
    base_doc = load(base)

    # D5-V1: SSOT key 가 base 에 있고, 같은 서비스 프로파일에는 재선언되지 않아야 한다.
    for key in SSOT_KEYS:
        base_present, _ = get_path(base_doc, key)
        if not base_present:
            violations.append(
                f"[D5-V1] SSOT key absent in service base:\n"
                f"  service: {module_dir}\n"
                f"  key: {'.'.join(key)}\n"
                f"  expected: {base}\n"
                f"  → ADR-0009 §Decision S2/S3 (ADR-0015 per-service): 각 서비스 base application.yml SSOT 필수.\n"
            )
            continue
        offenders = []
        for p in profiles:
            present, _ = get_path(load(p), key)
            if present:
                offenders.append(p)
        if offenders:
            violations.append(
                f"[D5-V1] SSOT location violation:\n"
                f"  service: {module_dir}\n"
                f"  key: {'.'.join(key)}\n"
                f"  base SSOT: {base}\n"
                f"  violating files: {', '.join(offenders)}\n"
                f"  → ADR-0009 §Decision S2/S3 (ADR-0015): 서비스 base application.yml SSOT only.\n"
            )

    # D5-V2 (yaml side): application 태그 값은 서비스 모듈명(<svc>-service)과 일치해야 한다.
    APP_TAG_PATH = ("management", "metrics", "tags", "application")
    all_files = [base] + profiles
    for p in all_files:
        present, val = get_path(load(p), APP_TAG_PATH)
        if present and val != module_dir:
            violations.append(
                f"[D5-V2] application tag value mismatch:\n"
                f"  file: {p}\n"
                f"  expected: {module_dir!r} (서비스 자기 이름)\n"
                f"  found:    {val!r}\n"
                f"  → ADR-0015 §Decision S2: per-service application 태그 = 모듈명.\n"
            )

if violations:
    sys.stdout.write("\n".join(violations) + "\n")
    sys.exit(1)
sys.exit(0)
PY
if [[ "$PY_RC" -eq 2 ]]; then
    exit 2
fi
if [[ "$PY_RC" -ne 0 ]]; then
    VIOLATIONS=$((VIOLATIONS + 1))
fi

# ---------- D5-V2 (Java side): MeterFilter / MeterRegistryCustomizer 단일 owner ----------
# ADR-0015 S1: MetricsConfig 는 peekcart-common-observability 모듈 1개소. 서비스 모듈 재선언 금지.
EXPECTED_OWNER="peekcart-common-observability/src/main/java/com/peekcart/global/config/MetricsConfig.java"

# MeterFilter / MeterRegistryCustomizer 식별자를 사용하는 .java 파일 광범위 grep (전체 모듈 src/main/java).
# narrow 패턴 (`new MeterFilter()`, `MeterRegistryCustomizer<`) 만 보면
# `@Bean MeterFilter foo() { return MeterFilter.deny(...); }` 같은 factory 방식을 놓침.
# import-only false positive 는 후속 grep 으로 필터링.
mapfile -t CANDIDATES < <(grep -rln -E '\b(MeterFilter|MeterRegistryCustomizer)\b' --include='*.java' */src/main/java 2>/dev/null || true)

OFFENDERS=()
for f in "${CANDIDATES[@]:-}"; do
    [[ -z "$f" ]] && continue
    [[ "$f" == "$EXPECTED_OWNER" ]] && continue
    # import 라인을 제외하고, 각 라인에서 '주석 부분만' 지운 뒤 남은 코드에 식별자가 있는지 본다.
    #
    # 왜 주석을 지우는가: 빈 선언은 주석 안에 있을 수 없다. 반대로 "여기서는
    # MeterRegistryCustomizer 를 선언하지 않는다" 같은 javadoc 은 정상이며, 이를 위반으로 잡으면
    # 설계 의도를 주석에 남기는 것 자체가 벌칙이 된다 (구현 ⑤ CacheConfig 에서 실제 오탐).
    #
    # 왜 '라인 통째 제외'가 아닌가: `/* note */ @Bean MeterFilter f() {...}` 처럼 주석 뒤에
    # 실제 선언이 이어지면 그 라인을 버리는 순간 D5-V2 가 우회된다. 주석 토큰만 지우고
    # 남은 코드를 검사해야 이 우회가 막힌다.
    #
    # sed: (1) `/* ... */` 한 줄 블록 제거 (2) `//` 이후 제거 (3) 블록주석 본문 라인(`*` 시작) 제거
    # 구분자는 `#` 를 쓴다 — `:` 를 쓰면 `grep -n` 접두사 캡처 `([0-9]+:)` 안의 `:` 가
    # s 명령을 끊어 치환이 통째로 망가진다(검출이 0건이 된다).
    non_import=$(grep -nE '\b(MeterFilter|MeterRegistryCustomizer)\b' "$f" \
                 | grep -vE '^\s*[0-9]+:\s*import\s' \
                 | sed -E 's#/\*[^*]*\*+([^/*][^*]*\*+)*/##g; s#//.*$##; s#^([0-9]+:)[[:space:]]*[*].*$#\1#' \
                 | grep -E '\b(MeterFilter|MeterRegistryCustomizer)\b' || true)
    if [[ -n "$non_import" ]]; then
        OFFENDERS+=("$f")
    fi
done

if [[ ${#OFFENDERS[@]} -gt 0 ]]; then
    {
        echo "[D5-V2] Duplicate declaration:"
        echo "  surface: S1 (MeterRegistryCustomizer / MeterFilter)"
        echo "  expected single owner: $EXPECTED_OWNER"
        echo "  also found in:"
        for o in "${OFFENDERS[@]}"; do
            echo "    - $o"
        done
        echo "  → ADR-0015 §Decision S1: 공유 모듈 1개소, 서비스 모듈 재선언 금지."
    } >&2
    VIOLATIONS=$((VIOLATIONS + 1))
fi

if [[ "$VIOLATIONS" -gt 0 ]]; then
    exit 1
fi
exit 0

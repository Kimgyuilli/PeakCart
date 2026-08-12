#!/usr/bin/env bash
# internal-key-ownership-lint — 내부 토큰 키 도메인 분리 강제 (ADR-0017 D3 · 구현 ③ PR3d-a P7)
#
# 무엇을 막는가:
#   Gateway 내부 토큰 공개키가 User 의 `app.jwt.rs256.public-keys` 에 섞이면, JwkController 가
#   레지스트리를 통째로 JWKS 로 게시하므로 내부 신뢰 앵커가 외부에 노출되고 회전/폐기 경로가 엉킨다.
#
# 왜 kid 대조만으로는 부족한가:
#   같은 키를 *다른 kid* 로 끼워 넣으면 이름 검사는 통과한다. 그래서 참조된 PEM 을 실제로 읽어
#   SPKI DER SHA-256 fingerprint 로 비교한다(재인코딩·개행 차이에 불변).
#
# 범위: 커밋된 서비스 설정(application*.yml)과 그 설정이 가리키는 PEM — 즉 **이미지 기본값**.
#   - 렌더된 k8s 매니페스트(ConfigMap override·워크로드 마운트) 기준 검사는 PR3d-b 에서
#     `scripts/workload-key-ownership-lint.sh` 로 분리했다. 검사 대상(소스 설정 ↔ 클러스터 렌더)이
#     다르고 전제(kubectl 필요)도 달라 한 스크립트에 섞으면 실패 원인이 흐려진다.
#   - 부팅된 Spring Environment 기준 검사는 5서비스 보안 통합테스트(keyDomainsAreSeparated)가 담당한다.
#
# 사용: bash scripts/internal-key-ownership-lint.sh [--self-test]
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

LINT_PY="$(mktemp -t internal-key-ownership-lint.XXXXXX.py)"
trap 'rm -f "$LINT_PY"' EXIT

cat > "$LINT_PY" <<'PYEOF'
import base64
import glob
import hashlib
import os
import re
import sys

root = sys.argv[1]
SERVICES = ["user-service", "product-service", "order-service", "payment-service", "notification-service"]

VIOLATIONS = []


def bad(code, message):
    """진단은 고유 ID 로 낸다 — self-test 가 부분 문자열이 아니라 ID+횟수로 대조한다(계획 loop3 #6)."""
    VIOLATIONS.append("[%s] %s" % (code, message))


def key_entries(text):
    """(dotted_path, kid, location) 목록. 별도 YAML 의존성 없이 들여쓰기로 경로를 추적한다.

    이 저장소의 키 설정은 항상 `- kid: X` / `  location: Y` 형태다. 형태가 달라지면 entry 가
    0 개로 잡히고, 그 경우 ITKO-001(검사 대상 0)이 vacuous-green 을 막는다.
    """
    out = []
    stack = []  # (indent, key)
    pending_kid = None
    pending_path = None
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        line = raw.strip()

        m = re.match(r"^-\s*kid\s*:\s*(\S+)$", line)
        if m:
            pending_kid = m.group(1).strip("\"'")
            pending_path = ".".join(k for _, k in stack)
            continue
        m = re.match(r"^location\s*:\s*(\S+)$", line)
        if m and pending_kid is not None:
            out.append((pending_path, pending_kid, m.group(1).strip("\"'")))
            pending_kid = None
            continue

        m = re.match(r"^([A-Za-z0-9_.-]+)\s*:\s*(.*)$", line)
        if m:
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, m.group(1)))
    return out


def resolve(location, service_dir):
    """classpath:/file: 위치를 실제 파일 경로로 해석한다. 못 찾으면 None."""
    if location.startswith("classpath:"):
        rel = location[len("classpath:"):].lstrip("/")
        for r in (os.path.join(root, service_dir, "src/main/resources"),
                  os.path.join(root, "peekcart-common-auth/src/main/resources"),
                  os.path.join(root, "common/src/main/resources")):
            p = os.path.join(r, rel)
            if os.path.isfile(p):
                return p
        return None
    if location.startswith("file:"):
        p = location[len("file:"):]
        p = p if os.path.isabs(p) else os.path.join(root, p)
        return p if os.path.isfile(p) else None
    return None


def fingerprint(pem_path):
    """SPKI DER SHA-256 — 같은 키는 어떤 kid/포맷으로 들어와도 같은 값이 된다."""
    try:
        with open(pem_path, encoding="utf-8") as f:
            body = f.read()
    except OSError:
        return None
    m = re.search(r"-----BEGIN PUBLIC KEY-----(.*?)-----END PUBLIC KEY-----", body, re.S)
    if not m:
        return None
    try:
        return hashlib.sha256(base64.b64decode("".join(m.group(1).split()))).hexdigest()
    except Exception:
        return None


internal_kids = set()
internal_fingerprints = set()
internal_kids_by_service = {}
jwt_entries = []

for svc in SERVICES:
    configs = sorted(glob.glob(os.path.join(root, svc, "src/main/resources/application*.yml")))
    if not configs:
        continue
    internal_kids_by_service.setdefault(svc, set())
    for cfg in configs:
        rel = os.path.relpath(cfg, root)
        for path, kid, loc in key_entries(open(cfg, encoding="utf-8").read()):
            if path.endswith("internal-token.public-keys"):
                internal_kids.add(kid)
                internal_kids_by_service[svc].add(kid)
                resolved = resolve(loc, svc)
                if resolved is None:
                    bad("ITKO-004", "%s: internal-token 공개키 위치를 해석할 수 없다: %s" % (rel, loc))
                    continue
                fp = fingerprint(resolved)
                if fp is None:
                    bad("ITKO-005", "%s: internal-token 공개키 PEM 을 읽을 수 없다: %s" % (rel, loc))
                else:
                    internal_fingerprints.add(fp)
            elif path.endswith("rs256.public-keys"):
                jwt_entries.append((rel, svc, kid, loc))

if not internal_kids:
    bad("ITKO-001", "어떤 서비스에도 app.internal-token.public-keys 가 없다 — 검사 대상이 없어 무의미한 통과가 된다")

# 서비스 단위로 확인한다 — 전체 합산으로 보면 한 서비스만 키를 갖고 있어도 통과해,
# 나머지 서비스가 부팅 시 fail-fast 로 죽는 것을 CI 가 놓친다(diff 리뷰 c3:2).
for svc, kids in sorted(internal_kids_by_service.items()):
    if not kids:
        bad("ITKO-006",
            "%s: app.internal-token.public-keys 가 없다 — 이 서비스는 Gateway 서명을 검증할 수 없어"
            " 부팅에 실패하거나 모든 요청을 거부한다" % svc)

for rel, svc, kid, loc in jwt_entries:
    if kid in internal_kids:
        bad("ITKO-002", "%s: app.jwt.rs256.public-keys 에 Gateway kid '%s' 가 있다 (JWKS 로 노출됨)" % (rel, kid))
    resolved = resolve(loc, svc)
    fp = fingerprint(resolved) if resolved else None
    if fp and fp in internal_fingerprints:
        bad("ITKO-003",
            "%s: app.jwt.rs256.public-keys 의 '%s' 가 Gateway 공개키와 동일한 키다"
            " (kid 만 바꾼 우회 — fingerprint %s)" % (rel, kid, fp[:16]))

if VIOLATIONS:
    print("internal-key-ownership-lint: 위반 %d 건" % len(VIOLATIONS))
    for v in VIOLATIONS:
        print("  " + v)
    sys.exit(1)

print("internal-key-ownership-lint: OK (internal kid %d개 · Gateway 키가 User JWKS 설정에 없음)"
      % len(internal_kids))
PYEOF

run_lint() {
    python3 "$LINT_PY" "$1"
}

# --self-test: 위반 시나리오를 실제로 재현해 lint 가 "고유 진단 ID" 로 잡는지 대조한다.
# non-zero 여부만 보면 다른 이유로 실패해도 통과로 오판한다(계획 loop2 #4 / loop3 #6).
self_test() {
    local tmp failures=0
    tmp="$(mktemp -d)"

    _case() {
        local name="$1" expect_code="$2" expect_count="$3" fixture="$4"
        local out rc=0 actual
        out="$(run_lint "$fixture" 2>&1)" || rc=$?
        if [[ "$expect_code" == "NONE" ]]; then
            if [[ $rc -ne 0 ]]; then
                echo "  ✗ $name: 통과해야 하는데 실패했다"
                printf '%s\n' "$out" | sed 's/^/      /'
                failures=$((failures + 1))
            else
                echo "  ✓ $name"
            fi
            return
        fi
        if [[ $rc -eq 0 ]]; then
            echo "  ✗ $name: 위반인데 통과했다 (false-green)"
            failures=$((failures + 1))
            return
        fi
        actual="$(printf '%s\n' "$out" | grep -c "\[${expect_code}\]" || true)"
        if [[ "$actual" != "$expect_count" ]]; then
            echo "  ✗ $name: ${expect_code} 기대 ${expect_count}건, 실제 ${actual}건"
            printf '%s\n' "$out" | sed 's/^/      /'
            failures=$((failures + 1))
            return
        fi
        echo "  ✓ $name (${expect_code} ×${expect_count})"
    }

    # 픽스처: user-service 하나만 있으면 계약 검사에 충분하다.
    _fixture() {
        local dir="$1" mode="$2" jwt_block=""
        mkdir -p "$dir/user-service/src/main/resources/keys" \
                 "$dir/peekcart-common-auth/src/main/resources/keys"
        openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp/gw.key" 2>/dev/null
        openssl rsa -in "$tmp/gw.key" -pubout \
            -out "$dir/peekcart-common-auth/src/main/resources/keys/gw-pub.pem" 2>/dev/null
        openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp/user.key" 2>/dev/null
        openssl rsa -in "$tmp/user.key" -pubout \
            -out "$dir/user-service/src/main/resources/keys/user-pub.pem" 2>/dev/null

        case "$mode" in
            same-kid)   jwt_block=$'        - kid: gw-kid\n          location: classpath:keys/gw-pub.pem' ;;
            other-kid)  jwt_block=$'        - kid: innocent-looking-kid\n          location: classpath:keys/gw-pub.pem' ;;
        esac

        {
            echo "app:"
            echo "  jwt:"
            echo "    rs256:"
            echo "      public-keys:"
            echo "        - kid: user-kid"
            echo "          location: classpath:keys/user-pub.pem"
            [[ -n "$jwt_block" ]] && printf '%s\n' "$jwt_block"
            echo "  internal-token:"
            echo "    mode: SIGNED_ONLY"
            echo "    public-keys:"
            echo "      - kid: gw-kid"
            echo "        location: classpath:keys/gw-pub.pem"
        } > "$dir/user-service/src/main/resources/application.yml"
    }

    echo "internal-key-ownership-lint --self-test"

    # (0) 정상: 현재 저장소 — 검사가 실제 배선을 통과시키는지
    _case "정상 배선(현 저장소)" NONE 0 "$(pwd)"

    # (1) 정상 픽스처 — 픽스처 자체가 무조건 실패하지 않음을 보인다(대조군)
    _fixture "$tmp/c0" clean
    _case "정상 배선(픽스처)" NONE 0 "$tmp/c0"

    # (2) 같은 kid 를 User JWKS 설정에 넣음
    _fixture "$tmp/c1" same-kid
    _case "Gateway kid 가 app.jwt.rs256 에 존재" ITKO-002 1 "$tmp/c1"

    # (3) 같은 키를 *다른 kid* 로 밀반입 — kid 대조만 하면 통과하는 우회
    _fixture "$tmp/c2" other-kid
    _case "같은 키를 다른 kid 로 밀반입" ITKO-003 1 "$tmp/c2"

    # (4) 내부 토큰 키가 아예 없음 → vacuous-green 방지
    mkdir -p "$tmp/c3/user-service/src/main/resources"
    {
        echo "app:"
        echo "  jwt:"
        echo "    rs256:"
        echo "      public-keys:"
        echo "        - kid: user-kid"
        echo "          location: classpath:keys/user-pub.pem"
    } > "$tmp/c3/user-service/src/main/resources/application.yml"
    _case "internal-token 키 부재(검사 대상 0)" ITKO-001 1 "$tmp/c3"

    # (5) 내부 토큰 키 위치가 깨짐
    mkdir -p "$tmp/c4/user-service/src/main/resources"
    {
        echo "app:"
        echo "  internal-token:"
        echo "    public-keys:"
        echo "      - kid: gw-kid"
        echo "        location: classpath:keys/nope.pem"
    } > "$tmp/c4/user-service/src/main/resources/application.yml"
    _case "internal-token 키 위치 해석 불가" ITKO-004 1 "$tmp/c4"

    # (6) 일부 서비스만 내부키를 갖는 경우 — 합산 검사였다면 통과하는 우회
    _fixture "$tmp/c5" clean
    mkdir -p "$tmp/c5/order-service/src/main/resources"
    {
        echo "app:"
        echo "  jwt:"
        echo "    rs256:"
        echo "      public-keys:"
        echo "        - kid: user-kid"
        echo "          location: classpath:keys/user-pub.pem"
    } > "$tmp/c5/order-service/src/main/resources/application.yml"
    _case "일부 서비스만 내부키 보유" ITKO-006 1 "$tmp/c5"

    rm -rf "$tmp"
    if [[ $failures -gt 0 ]]; then
        echo "self-test 실패 ${failures}건"
        return 1
    fi
    echo "self-test OK (7/7)"
}

if [[ "${1:-}" == "--self-test" ]]; then
    self_test
else
    run_lint "$(pwd)"
fi

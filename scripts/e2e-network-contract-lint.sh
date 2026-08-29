#!/usr/bin/env bash
# e2e-network-contract-lint — E2E 스택의 격리 계약 (계획 P3 · P16 · N2)
#
# 무엇을 막는가:
#   N2 는 "E2E 가 외부 PG 로 나가는 경로가 하나라도 열려 있으면 미완" 이다. 이걸 런타임에서
#   "stub 을 내리면 실패한다" 로 확인하면 **false-green** 이다 — internal:true 가 삭제돼
#   외부로 나갈 수 있는 상태에서도 stub DNS 실패로 똑같이 실패하기 때문이다.
#   그래서 "무엇에 붙어 있는가" 를 정적으로 고정한다.
#
# 무엇을 검사하는가:
#   1) 앱·인프라·stub·runner 가 **오직** internal 네트워크에만 부착
#   2) internal 네트워크가 실제로 internal: true
#   3) 격리 대상 서비스에 호스트 포트(ports:) 노출 0
#   4) container_name 사용 0 (project 병렬 기동)
#   5) 양성 대조군(egress-control)은 **비격리** 네트워크에 있어야 한다 —
#      이게 없으면 P16 의 canary 검사가 음성만 보게 되어 다시 false-green 이 된다
#
# 사용: bash scripts/e2e-network-contract-lint.sh [--self-test]
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

LINT_PY="$(mktemp -t e2e-network-contract-lint.XXXXXX.py)"
trap 'rm -f "$LINT_PY"' EXIT

cat > "$LINT_PY" <<'PYEOF'
import sys
import yaml

path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    doc = yaml.safe_load(f)

services = doc.get("services") or {}
networks = doc.get("networks") or {}

INTERNAL = "internal"
CONTROL = "external-control"
# 격리돼야 하는 것 = 앱 4 + 인프라 3 + stub + runner. 양성 대조군만 예외다.
CONTROL_ONLY = {"egress-control", "egress-canary"}
# 격리 대상 정본. "붙은 것들이 internal 전용인가" 만 보면 **서비스를 지운 파일이 통과한다**
# — 검사 대상이 줄어들 뿐이라 위반이 0이 된다(#92 후속 리뷰 #1 이 fixture 로 반증).
REQUIRED = {
    "mysql", "kafka", "redis",
    "order-service", "product-service", "payment-service", "notification-service",
    "pg-stub", "runner",
}

violations = []

if not services:
    violations.append("services 가 비어 있다 — 파일을 잘못 읽었다")

missing = REQUIRED - set(services)
if missing:
    violations.append(f"격리 대상 서비스 누락: {sorted(missing)} — 삭제는 검사 대상만 줄인다")

net = networks.get(INTERNAL)
if net is None:
    violations.append(f"networks.{INTERNAL} 이 없다")
elif not (isinstance(net, dict) and net.get("internal") is True):
    violations.append(f"networks.{INTERNAL}.internal 이 true 가 아니다 — 외부 egress 가 열린다")

ctrl = networks.get(CONTROL)
if ctrl is None:
    violations.append(f"networks.{CONTROL} 이 없다 — canary 양성 대조군이 붙을 곳이 없다")
elif isinstance(ctrl, dict) and ctrl.get("internal") is True:
    violations.append(f"networks.{CONTROL}.internal 이 true 다 — 양성 대조군이 대조군 구실을 못 한다")

for name, spec in services.items():
    spec = spec or {}
    nets = spec.get("networks")
    if nets is None:
        violations.append(f"{name}: networks 미지정 — 기본 네트워크에 붙어 외부로 나간다")
        continue
    if isinstance(nets, dict):
        nets = list(nets.keys())
    nets = list(nets)

    if name in CONTROL_ONLY:
        if nets != [CONTROL]:
            violations.append(f"{name}: 양성 대조군은 [{CONTROL}] 에만 붙어야 한다 (현재 {nets})")
        continue

    if nets != [INTERNAL]:
        violations.append(f"{name}: 오직 [{INTERNAL}] 에만 붙어야 한다 (현재 {nets})")

    if spec.get("ports"):
        violations.append(f"{name}: 호스트 포트 노출 {spec['ports']} — project 병렬 기동이 충돌한다")

    if spec.get("network_mode"):
        violations.append(f"{name}: network_mode 사용 — 네트워크 격리를 우회한다")

    if spec.get("container_name"):
        violations.append(f"{name}: container_name 고정 — project 병렬 기동이 충돌한다")

# **집합 전체**를 요구한다. "하나라도 보였는가" 로 두면 표적(egress-canary)이나 프로브
# (egress-control) 중 하나가 사라져도 통과하고, 그러면 양성 대조가 성립하지 않는다.
missing_control = CONTROL_ONLY - set(services)
if missing_control:
    violations.append(
        f"양성 대조군 서비스 누락: {sorted(missing_control)} — 음성 대조만 남으면 "
        "격리가 아니라 '아무 이유로든 실패' 를 격리로 오인한다")

if violations:
    print("\n".join(f"  - {v}" for v in violations))
    sys.exit(1)
print(f"OK — 격리 서비스 {len(services) - len(CONTROL_ONLY)}개 · internal 전용 · 대조군 1")
PYEOF

run_lint() {
  python3 "$LINT_PY" "$1"
}

if [[ "${1:-}" == "--self-test" ]]; then
  # 조작 입력에서 실제로 실패하는지 — lint 자체가 vacuous-green 으로 썩는 것을 막는다.
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"; rm -f "$LINT_PY"' EXIT
  fails=0
  check() {
    local name="$1" file="$2"
    if run_lint "$file" >/dev/null 2>&1; then
      echo "  FAIL [$name] 통과해서는 안 되는 입력이 통과했다"
      fails=$((fails + 1))
    else
      echo "  ok   [$name]"
    fi
  }

  mutate() {  # $1=출력, $2=python 변형식
    python3 - "$1" "$2" <<'PYEOF'
import sys, yaml
out, expr = sys.argv[1], sys.argv[2]
doc = yaml.safe_load(open("docker-compose.e2e.yml", encoding="utf-8"))
exec(expr, {"doc": doc})
yaml.safe_dump(doc, open(out, "w", encoding="utf-8"))
PYEOF
  }

  echo "e2e-network-contract-lint self-test"
  mutate "$TMP/1.yml" "doc['networks']['internal']['internal'] = False"
  check "internal:true 제거" "$TMP/1.yml"

  mutate "$TMP/2.yml" "doc['services']['payment-service']['networks'] = ['internal', 'external-control']"
  check "앱에 보조 외부 네트워크 부착" "$TMP/2.yml"

  mutate "$TMP/3.yml" "doc['services']['mysql']['ports'] = ['3306:3306']"
  check "호스트 포트 노출" "$TMP/3.yml"

  mutate "$TMP/4.yml" "doc['services']['redis']['container_name'] = 'peekcart-redis'"
  check "container_name 고정" "$TMP/4.yml"

  mutate "$TMP/5.yml" "doc['services'].pop('egress-control')"
  check "양성 대조군 삭제" "$TMP/5.yml"

  mutate "$TMP/5b.yml" "doc['services'].pop('egress-canary')"
  check "양성 대조군 표적(canary) 삭제" "$TMP/5b.yml"

  mutate "$TMP/6.yml" "doc['networks']['external-control']['internal'] = True"
  check "대조군 네트워크까지 격리" "$TMP/6.yml"

  mutate "$TMP/7.yml" "doc['services']['order-service'].pop('networks')"
  check "networks 미지정(기본 네트워크 부착)" "$TMP/7.yml"

  mutate "$TMP/8.yml" "doc['services']['product-service']['network_mode'] = 'host'"
  check "network_mode: host" "$TMP/8.yml"

  # 서비스 삭제 대조군 — REQUIRED 집합이 없으면 위반 0 으로 조용히 통과한다
  mutate "$TMP/9.yml" "doc['services'].pop('payment-service')"
  check "격리 대상 서비스 삭제(payment-service)" "$TMP/9.yml"

  mutate "$TMP/10.yml" "doc['services'].pop('pg-stub')"
  check "격리 대상 서비스 삭제(pg-stub)" "$TMP/10.yml"

  # 양성: 원본은 통과해야 한다
  if run_lint "docker-compose.e2e.yml" >/dev/null 2>&1; then
    echo "  ok   [원본 통과]"
  else
    echo "  FAIL [원본 통과] 정상 입력이 실패했다"
    fails=$((fails + 1))
  fi

  if [[ $fails -gt 0 ]]; then
    echo "self-test 실패 ${fails}건"
    exit 1
  fi
  echo "self-test 통과 (12종)"
  exit 0
fi

if ! run_lint "docker-compose.e2e.yml"; then
  echo "::error::[e2e-network-contract-lint] E2E 격리 계약 위반 — 외부 egress 경로가 열린다 (계획 P3 · N2)" >&2
  exit 1
fi

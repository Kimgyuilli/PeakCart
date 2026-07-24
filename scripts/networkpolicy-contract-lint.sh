#!/usr/bin/env bash
# networkpolicy-contract-lint.sh — backend NetworkPolicy 계약 정적 검증 (ADR-0013 D3 · 구현 ③ PR3c)
#
# 목적: 양 overlay 의 kubectl kustomize 산출물에서 backend ingress 제한 정책을 검사한다.
#       "0개 workload 를 선택하는 정책"·"gateway peer 누락"·"monitoring 예외 누락"·"egress 규칙 혼입"
#       은 kustomize 렌더와 기존 lint 를 전부 통과한다(vacuous-green) → 렌더 성공은 계약 검증이 아니다.
#
# 검사(계획 P35 · review #7):
#   ① 정책 podSelector 가 렌더된 backend Deployment pod(component: backend)를 실제로 선택 (0개면 실패)
#   ② gateway ingress peer(app: gateway) 존재
#   ③ monitoring namespace 의 Prometheus scrape 예외 peer 존재
#   ④ policyTypes == [Ingress] (egress 규칙 부재 — ingress-only 계약)
#   ⑤ 대상에 gateway pod(component: gateway) 불포함
#
# 검사하지 않는 것(소유권 분계):
#   - gateway 노출 표면 → scripts/gateway-exposure-lint.sh
#   - ServiceMonitor 집합 → scripts/servicemonitor-selector-lint.sh
#
# 자기검증: `bash scripts/networkpolicy-contract-lint.sh --self-test` — 무변조 baseline 통과 +
#           조작 입력 5종이 **의도한 검사에** 걸리는지 진단 문자열까지 대조(vacuous-green 차단).
#
# Exit: 0 위반 없음(또는 kubectl 미존재 skip) / 1 위반 / 2 전제 미충족(pyyaml·self-test 실패)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TAG="[NP-CONTRACT]"

if ! command -v kubectl >/dev/null 2>&1; then
    echo "$TAG kubectl not found — skipping (CI 에서는 azure/setup-kubectl@v4 로 설치)"
    exit 0
fi

if ! python3 -c 'import yaml' 2>/dev/null; then
    echo "$TAG pyyaml 미설치 — \`python3 -m pip install --user pyyaml\` 필요" >&2
    exit 2
fi

CHECKER="$(mktemp)"
trap 'rm -f "$CHECKER"' EXIT

cat >"$CHECKER" <<'PY'
import os, sys, yaml

overlay = os.environ["OVERLAY_NAME"]
path = os.environ["OVERLAY_OUT"]
TAG = "[NP-CONTRACT]"

with open(path) as f:
    docs = [d for d in yaml.safe_load_all(f) if d]

v = []
def bad(msg):
    v.append(f"{TAG} {overlay}: {msg}")

BACKEND_LABEL = ("app.kubernetes.io/component", "backend")
BACKEND_PORT = 8080
# 보호 대상 = 고정된 5개 Deployment 이름(review #3). "component: backend 인 workload 집합" 으로
# 대상을 정의하면, 한 서비스의 pod 라벨이 gateway 로 드리프트했을 때 그 서비스가 대상에서 빠져
# "나머지 4/4 통과" 로 false-green 이 된다. 이름으로 고정하고 각각 독립 검증한다.
EXPECTED_BACKENDS = ["user-service", "product-service", "order-service",
                     "payment-service", "notification-service"]
GATEWAY_DEPLOY = "gateway"


def pod_labels_of(doc):
    tmpl = ((doc.get("spec") or {}).get("template") or {})
    return (tmpl.get("metadata") or {}).get("labels") or {}


def selects(match_labels, labels):
    if not match_labels:
        return False
    return all(labels.get(k) == val for k, val in match_labels.items())


deploys = {d["metadata"]["name"]: d
           for d in docs if d.get("kind") == "Deployment"}

# 정본 정책은 이름으로 식별한다(계약 anchor).
POLICY_NAME = "backend-ingress-gateway-only"
policies = [d for d in docs if d.get("kind") == "NetworkPolicy"]
backend_policies = [p for p in policies if p["metadata"]["name"] == POLICY_NAME]

if len(backend_policies) != 1:
    bad(f"NetworkPolicy '{POLICY_NAME}' 가 {len(backend_policies)}개 "
        f"(정확히 1개여야 함): {[p['metadata']['name'] for p in policies]}")
    print("\n".join(v), file=sys.stderr)
    sys.exit(1)

pol = backend_policies[0]
spec = pol.get("spec") or {}
pod_selector = (spec.get("podSelector") or {}).get("matchLabels") or {}

# ① 5개 backend Deployment 각각을 이름으로 찾아 (a) pod 라벨 component:backend (b) 정책 선택 을 독립 검증.
#    라벨 드리프트(component→gateway 등)면 (a) 가 잡고, selector 오타면 (b) 가 잡는다.
for name in EXPECTED_BACKENDS:
    dep = deploys.get(name)
    if dep is None:
        bad(f"backend Deployment '{name}' 가 렌더에 없음")
        continue
    labels = pod_labels_of(dep)
    if labels.get(BACKEND_LABEL[0]) != BACKEND_LABEL[1]:
        bad(f"'{name}' pod 라벨 {BACKEND_LABEL[0]}={labels.get(BACKEND_LABEL[0])} "
            f"(기대 backend) — 라벨 드리프트, 정책이 보호하지 못한다")
    if not selects(pod_selector, labels):
        bad(f"정책 podSelector({pod_selector}) 가 '{name}' 를 선택하지 않음 — 보호 누락")

# ⑤ 대상에 gateway pod 가 포함되면 안 됨(자기 자신을 격리하면 진입점이 막힌다)
gw_dep = deploys.get(GATEWAY_DEPLOY)
if gw_dep is not None and selects(pod_selector, pod_labels_of(gw_dep)):
    bad("정책 podSelector 가 gateway pod 도 선택함 — 진입점을 격리하면 안 된다")

# ④ ingress-only 계약: policyTypes == [Ingress], egress 규칙 부재
ptypes = spec.get("policyTypes") or []
if ptypes != ["Ingress"]:
    bad(f"policyTypes 가 {ptypes} — ingress-only 계약상 정확히 [Ingress] 여야 한다(egress 격리 금지)")
if spec.get("egress"):
    bad("egress 규칙이 선언됨 — ingress-only 계약 위반(DB/Redis/DNS 를 끊는다)")

# ② ③ peer 는 **포트와 결합**해 검사한다(review #5) — peer 존재만 보면 포트 오타·podSelector 누락을 놓친다.
ingress = spec.get("ingress") or []

def rule_matches(peer_pred):
    """peer_pred 를 만족하는 from 을 가지면서 TCP {BACKEND_PORT} 를 여는 ingress rule 이 있는가."""
    for rule in ingress:
        froms = rule.get("from") or []
        ports = rule.get("ports") or []
        peer_ok = any(peer_pred(p) for p in froms)
        port_ok = any(pt.get("port") == BACKEND_PORT and (pt.get("protocol") or "TCP") == "TCP"
                      for pt in ports)
        if peer_ok and port_ok:
            return True
    return False

def is_gateway_peer(peer):
    ps = (peer.get("podSelector") or {}).get("matchLabels") or {}
    return ps.get("app") == "gateway" and not peer.get("namespaceSelector")

def is_monitoring_peer(peer):
    ns = (peer.get("namespaceSelector") or {}).get("matchLabels") or {}
    ps = (peer.get("podSelector") or {}).get("matchLabels") or {}
    return (ns.get("kubernetes.io/metadata.name") == "monitoring"
            and ps.get("app.kubernetes.io/name") == "prometheus")

if not rule_matches(is_gateway_peer):
    bad(f"gateway ingress peer(app: gateway) + TCP {BACKEND_PORT} 결합 규칙이 없음 — 진입 경로가 막히거나 포트 불일치")
if not rule_matches(is_monitoring_peer):
    bad(f"monitoring scrape 예외(namespace monitoring + prometheus podSelector) + TCP {BACKEND_PORT} 규칙이 없음 "
        "— Prometheus scrape 차단 시 관측성 붕괴")

if v:
    print("\n".join(v), file=sys.stderr)
    sys.exit(1)
print(f"{TAG} {overlay}: backend NetworkPolicy 계약 OK ({len(EXPECTED_BACKENDS)} backend 보호)")
PY

run_checks() {
    local overlays=("k8s/overlays/minikube" "k8s/overlays/gke")
    local tmp_dir violations=0
    tmp_dir="$(mktemp -d)"
    for overlay in "${overlays[@]}"; do
        local out="$tmp_dir/$(basename "$overlay").yml"
        if ! kubectl kustomize "$overlay" >"$out" 2>"$tmp_dir/err"; then
            echo "$TAG kubectl kustomize failed for $overlay:" >&2
            cat "$tmp_dir/err" >&2
            violations=$((violations + 1))
            continue
        fi
        if ! OVERLAY_NAME="$(basename "$overlay")" OVERLAY_OUT="$out" python3 "$CHECKER"; then
            violations=$((violations + 1))
        fi
    done
    rm -rf "$tmp_dir"
    return "$violations"
}

# ---------- self-test: 조작 입력 5종에서 반드시 실패해야 한다 ----------
if [[ "${1:-}" == "--self-test" ]]; then
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"; rm -f "$CHECKER"' EXIT
    kubectl kustomize k8s/overlays/gke >"$TMP/base.yml"

    mutate() {
        MUT="$1" SRC="$TMP/base.yml" python3 - <<'PY'
import os, yaml, sys
docs = [d for d in yaml.safe_load_all(open(os.environ["SRC"])) if d]
mut = os.environ["MUT"]
pol = next(d for d in docs if d["kind"] == "NetworkPolicy"
           and d["metadata"]["name"] == "backend-ingress-gateway-only")
spec = pol["spec"]

def gateway_rule():
    return next(r for r in spec["ingress"]
                if any((p.get("podSelector") or {}).get("matchLabels", {}).get("app") == "gateway"
                       for p in (r.get("from") or [])))
def monitoring_rule():
    return next(r for r in spec["ingress"]
                if any((p.get("namespaceSelector") or {}).get("matchLabels", {}).get(
                    "kubernetes.io/metadata.name") == "monitoring" for p in (r.get("from") or [])))

if mut == "selector_typo":
    # podSelector 오타 → backend 미선택(보호 누락)
    spec["podSelector"]["matchLabels"] = {"app.kubernetes.io/component": "backendd"}
elif mut == "backend_label_drift":
    # 실제 backend Deployment 하나의 pod 라벨을 gateway 로 드리프트 → 그 서비스가 보호에서 빠진다(review #3).
    dep = next(d for d in docs if d["kind"] == "Deployment" and d["metadata"]["name"] == "product-service")
    dep["spec"]["template"]["metadata"]["labels"]["app.kubernetes.io/component"] = "gateway"
elif mut == "drop_gateway_peer":
    spec["ingress"] = [r for r in spec["ingress"]
                       if not any((p.get("podSelector") or {}).get("matchLabels", {}).get("app") == "gateway"
                                  for p in (r.get("from") or []))]
elif mut == "drop_monitoring_peer":
    spec["ingress"] = [r for r in spec["ingress"]
                       if not any((p.get("namespaceSelector") or {}).get("matchLabels", {}).get(
                           "kubernetes.io/metadata.name") == "monitoring"
                                  for p in (r.get("from") or []))]
elif mut == "gateway_wrong_port":
    # gateway peer 는 있으나 포트가 8081 → 진입 포트 불일치(review #5)
    gateway_rule()["ports"] = [{"protocol": "TCP", "port": 8081}]
elif mut == "monitoring_no_prom_selector":
    # monitoring namespace 만 있고 prometheus podSelector 누락 → 과허용(review #5)
    for p in monitoring_rule()["from"]:
        p.pop("podSelector", None)
elif mut == "add_egress":
    spec["policyTypes"] = ["Ingress", "Egress"]
    spec["egress"] = [{"to": [{"podSelector": {"matchLabels": {"app": "mysql"}}}]}]
elif mut == "catch_gateway":
    spec["podSelector"]["matchLabels"] = {"app.kubernetes.io/part-of": "peekcart"}
else:
    raise SystemExit(f"unknown mutation {mut}")

yaml.safe_dump_all(docs, sys.stdout)
PY
    }

    declare -A EXPECT=(
        [selector_typo]="선택하지 않음"
        [backend_label_drift]="라벨 드리프트"
        [drop_gateway_peer]="gateway ingress peer"
        [drop_monitoring_peer]="monitoring scrape 예외"
        [gateway_wrong_port]="gateway ingress peer"
        [monitoring_no_prom_selector]="monitoring scrape 예외"
        [add_egress]="egress"
        [catch_gateway]="gateway pod 도 선택"
    )
    MUTATIONS=(selector_typo backend_label_drift drop_gateway_peer drop_monitoring_peer
               gateway_wrong_port monitoring_no_prom_selector add_egress catch_gateway)

    if ! OVERLAY_NAME="gke" OVERLAY_OUT="$TMP/base.yml" python3 "$CHECKER" >/dev/null 2>&1; then
        echo "$TAG self-test FAILED — 무변조 baseline 이 실패한다" >&2
        exit 2
    fi
    echo "$TAG self-test ok — baseline 통과"

    failures=0
    for m in "${MUTATIONS[@]}"; do
        mutate "$m" >"$TMP/mutated.yml"
        if OVERLAY_NAME="gke" OVERLAY_OUT="$TMP/mutated.yml" python3 "$CHECKER" >/dev/null 2>"$TMP/diag"; then
            echo "$TAG self-test FAILED — 조작 입력 '$m' 을 통과시킴(vacuous green)" >&2
            failures=$((failures + 1))
        elif ! grep -qF "${EXPECT[$m]}" "$TMP/diag"; then
            echo "$TAG self-test FAILED — '$m' 이 실패하긴 했으나 의도한 검사가 아니다" >&2
            echo "  기대 진단: ${EXPECT[$m]}" >&2
            sed 's/^/  실제: /' "$TMP/diag" >&2
            failures=$((failures + 1))
        else
            echo "$TAG self-test ok — '$m' 차단 (${EXPECT[$m]})"
        fi
    done
    if [[ "$failures" -gt 0 ]]; then
        echo "$TAG self-test 실패 ${failures}/${#MUTATIONS[@]}" >&2
        exit 2
    fi
    echo "$TAG self-test 통과 (${#MUTATIONS[@]}/${#MUTATIONS[@]} 차단)"
    exit 0
fi

if ! run_checks; then
    echo "$TAG backend NetworkPolicy 계약 위반 — ingress 제한이 계획(ADR-0013 D3 · PR3c)과 다르다" >&2
    exit 1
fi
echo "$TAG backend NetworkPolicy 계약 OK — overlays: minikube, gke"

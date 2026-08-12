#!/usr/bin/env bash
# rollout-convergence-gate.sh — 롤아웃 단계 간 "전량 수렴" hard gate (계획 §7 · §8 loop3 #1 · 구현 ③ PR3d-b)
#
# 왜 필요한가:
#   `maxUnavailable=0` 은 **가용성**만 보장한다. 수렴은 보장하지 않는다. 구 gateway 가 아직 평문을
#   주입하는 동안 일부 서비스만 signed-only 로 넘어가면, 살아 있는 Pod 들이 서로 다른 계약을 말하는
#   구간이 생기고 그 사이 요청은 401 이 된다. 그래서 각 단계는 **다음 단계 진입 전에** 전량 수렴을
#   관측으로 확인해야 한다 — `kubectl rollout status` 는 이 중 일부(가용성)만 본다.
#
# 판정식 (관측 가능한 것만):
#   (a) status.observedGeneration == metadata.generation   — 컨트롤러가 최신 spec 을 봤다
#   (b) updated == ready == available == spec.replicas      — 새 Pod 로 전부 교체됐다
#   (c) unavailableReplicas == 0                            — 교체 중인 Pod 가 없다
#   (d) 구 ReplicaSet 의 replicas == 0                      — 롤백 대기 Pod 가 남아 있지 않다
#   (e) 모든 Pod 의 컨테이너 image 가 기대값과 일치           — 다른 태그가 섞여 있지 않다
#
# "평문 주입 0" (§7 ③ gate) 은 어떻게 관측하는가:
#   무트래픽 상태에서는 "평문을 주입하지 않았다" 가 자동으로 참이라 관측이 되지 않는다. 그래서
#   **gateway Pod 하나하나에 개별 synthetic 요청**을 보내 판정한다 — 리소스 서비스가 SIGNED_ONLY 인
#   상태에서 보호 경로가 200 이면 그 Pod 는 유효한 X-Internal-Auth 를 주입한 것이고, 구 이미지처럼
#   평문을 주입했다면 서비스가 신원을 세우지 않아 401 이 된다. 즉 per-Pod 200 이 곧 평문 주입 0 이다.
#   (Service 를 통해 N번 때리는 방식은 로드밸런싱 때문에 특정 Pod 를 보증하지 못한다.)
#
# 사용:
#   bash scripts/rollout-convergence-gate.sh --workloads gateway,order-service
#   bash scripts/rollout-convergence-gate.sh --workloads gateway --expect-image <ref>
#   bash scripts/rollout-convergence-gate.sh --gateway-signing-probe   # §7 ③/④ gate
#
# Exit: 0 수렴 / 1 미수렴 / 2 전제 미충족
set -euo pipefail

NAMESPACE="${NAMESPACE:-peekcart}"
TAG="[ROLLOUT-GATE]"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"
POLL_SECONDS="${POLL_SECONDS:-10}"

command -v kubectl >/dev/null 2>&1 || { echo "$TAG kubectl 없음" >&2; exit 2; }

WORKLOADS=""
EXPECT_IMAGE=""
SIGNING_PROBE=0
ALLOW_ANY_IMAGE=0
while [ $# -gt 0 ]; do
    case "$1" in
        --workloads) WORKLOADS="$2"; shift 2 ;;
        --expect-image) EXPECT_IMAGE="$2"; shift 2 ;;
        --allow-any-image) ALLOW_ANY_IMAGE=1; shift ;;
        --gateway-signing-probe) SIGNING_PROBE=1; shift ;;
        *) echo "$TAG 알 수 없는 인자: $1" >&2; exit 2 ;;
    esac
done

# digest 기대값 없이 도는 수렴 gate 는 "무엇으로 수렴했는지" 를 말하지 못한다 — mutable tag 가
# Pod 마다 다른 digest 로 풀려도 통과한다. 단계마다 고유 태그를 쓰는 게 §12 rollback 행렬의 전제이므로
# 기본을 필수로 두고, 의도적 생략만 명시 플래그로 허용한다.
if [ -n "$WORKLOADS" ] && [ -z "$EXPECT_IMAGE" ] && [ "$ALLOW_ANY_IMAGE" -eq 0 ]; then
    echo "$TAG --expect-image <digest 포함 ref> 가 필요하다 (의도적 생략은 --allow-any-image)" >&2
    exit 2
fi

# ── (a)~(e) 워크로드 수렴 ──
converged() {
    local wl="$1"
    kubectl -n "$NAMESPACE" get deployment "$wl" -o json 2>/dev/null | EXPECT_IMAGE="$EXPECT_IMAGE" python3 -c '
import json, os, sys

d = json.load(sys.stdin)
name = d["metadata"]["name"]
st = d.get("status") or {}
desired = (d.get("spec") or {}).get("replicas", 1)
problems = []

observed = st.get("observedGeneration")
generation = d["metadata"].get("generation")
if observed != generation:
    problems.append("observedGeneration=" + str(observed) + " != generation=" + str(generation))
for field in ("updatedReplicas", "readyReplicas", "availableReplicas"):
    if st.get(field, 0) != desired:
        problems.append(field + "=" + str(st.get(field, 0)) + " != desired=" + str(desired))
unavailable = st.get("unavailableReplicas", 0)
if unavailable not in (0, None):
    problems.append("unavailableReplicas=" + str(unavailable))

expect = os.environ.get("EXPECT_IMAGE") or ""
if expect:
    imgs = [c["image"] for c in d["spec"]["template"]["spec"]["containers"]]
    # 부분 일치 — 기대값은 digest 조각(sha256:...)일 수도, 전체 ref 일 수도 있다.
    if not any(expect in i for i in imgs):
        problems.append("기대 이미지 " + expect + " 가 spec 에 없다: " + str(imgs))

if problems:
    print(name + ": " + " · ".join(problems))
    sys.exit(1)
print(name + ": 수렴 (replicas " + str(desired) + "/" + str(desired) + ")")
'
}

old_replicasets_drained() {
    local wl="$1" rs_json active
    # 구 RS 가 replicas>0 으로 남아 있으면 롤백 대기 Pod 가 있다는 뜻 — 계약이 두 개 공존한다.
    #
    # 조회 실패·0건을 성공으로 흘리지 않는다: `| awk | wc -l` 파이프라인은 kubectl 이 죽어도
    # "0" 을 뱉고, 0 은 "구 RS 없음(좋음)" 과 "아무것도 못 봤음(모름)" 을 구분하지 못한다.
    # 검사를 수행하지 못한 상태는 수렴이 아니라 **판정 불가(exit 2 상당)** 다.
    if ! rs_json=$(kubectl -n "$NAMESPACE" get rs -l "app=$wl" -o json 2>/dev/null); then
        echo "$TAG   $wl: ReplicaSet 조회 실패 — 수렴 여부를 판정할 수 없다(RBAC/context 확인)" >&2
        return 1
    fi
    # 소유 관계로 식별한다 — 라벨만 보면 selector drift 시 남의 RS 를 세거나 자기 RS 를 놓친다.
    active=$(WL="$wl" python3 -c '
import json, os, sys

wl = os.environ["WL"]
items = json.load(sys.stdin).get("items", [])
owned = [r for r in items
         if any(o.get("kind") == "Deployment" and o.get("name") == wl
                for o in (r["metadata"].get("ownerReferences") or []))]
if not owned:
    print("NONE")
    sys.exit(0)
active = [r["metadata"]["name"] for r in owned if (r.get("spec") or {}).get("replicas", 0) > 0]
print(" ".join(active) if active else "ZERO")
' <<<"$rs_json")

    case "$active" in
        NONE)
            echo "$TAG   $wl: Deployment 가 소유한 ReplicaSet 이 0건 — 검사 대상이 없다(판정 불가)" >&2
            return 1 ;;
        ZERO)
            echo "$TAG   $wl: 활성 ReplicaSet 이 0개 — Pod 가 없다(수렴 아님)" >&2
            return 1 ;;
        *)
            if [ "$(printf '%s\n' $active | wc -l | tr -d ' ')" -eq 1 ]; then
                echo "$TAG   $wl: 활성 ReplicaSet 1개($active) — 구 RS drain 완료"
                return 0
            fi
            echo "$TAG   $wl: 활성 ReplicaSet 이 여러 개($active) — 구 세대가 남아 있다" >&2
            return 1 ;;
    esac
}

# 실행 중인 Pod 의 imageID(digest)까지 대조한다 — spec 의 태그 문자열은 mutable tag 가 서로 다른
# digest 로 풀려도 같아 보인다. initContainer/sidecar 도 포함한다.
pods_match_digest() {
    local wl="$1" expect="$2" pods_json
    if ! pods_json=$(kubectl -n "$NAMESPACE" get pods -l "app=$wl" -o json 2>/dev/null); then
        echo "$TAG   $wl: Pod 조회 실패 — digest 대조 불가" >&2
        return 1
    fi
    EXPECT="$expect" WL="$wl" python3 -c '
import json, os, sys

expect = os.environ["EXPECT"]
wl = os.environ["WL"]
items = json.load(sys.stdin).get("items", [])
if not items:
    print(f"  {wl}: Pod 가 0건 — digest 대조 대상이 없다")
    sys.exit(1)
bad = []
for p in items:
    st = p.get("status") or {}
    for field in ("containerStatuses", "initContainerStatuses", "ephemeralContainerStatuses"):
        for cs in (st.get(field) or []):
            ref = cs.get("imageID") or cs.get("image") or ""
            if expect not in ref:
                bad.append(p["metadata"]["name"] + "/" + str(cs.get("name")) + "=" + ref)
if bad:
    print("  " + wl + ": 기대 digest(" + expect + ") 와 다른 이미지 " + ", ".join(bad))
    sys.exit(1)
print("  " + wl + ": 전 Pod image digest 일치 (" + str(len(items)) + " Pod)")
' <<<"$pods_json"
}

# ── gateway Pod 별 서명 주입 확인 ──
# 이 probe 의 판정은 "다운스트림이 SIGNED_ONLY 일 때만" 성립한다.
#
# 200 을 서명 주입의 증거로 삼는 논증은 "평문을 받으면 서비스가 401 을 준다" 에 의존한다. §7 ③ 구간의
# 다운스트림은 아직 **DUAL_ACCEPT** 라 평문도 수용하므로, 구 gateway 가 평문만 주입해도 200 이 나온다
# → "평문 주입 0" 이 false-green 이 된다. 그래서 전제를 먼저 관측으로 확인하고, 아니면 거부한다.
assert_downstream_signed_only() {
    local svc mode offenders=""
    for svc in user-service product-service order-service payment-service notification-service; do
        # base 기본값이 SIGNED_ONLY 이므로 env 부재 = SIGNED_ONLY. DUAL_ACCEPT 는 명시 override 로만 켜진다.
        mode=$(kubectl -n "$NAMESPACE" get deployment "$svc" -o json 2>/dev/null \
               | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print("UNKNOWN"); raise SystemExit(0)
found = ""
for c in d["spec"]["template"]["spec"]["containers"]:
    for e in (c.get("env") or []):
        if e.get("name") == "APP_INTERNALTOKEN_MODE":
            found = e.get("value") or ""
print(found or "SIGNED_ONLY")
' 2>/dev/null || echo "UNKNOWN")
        case "$mode" in
            SIGNED_ONLY) ;;
            *) offenders="$offenders $svc=$mode" ;;
        esac
    done
    # env 는 ConfigMap(envFrom)으로도 올 수 있다 — 그쪽까지 본다.
    if kubectl -n "$NAMESPACE" get configmap internal-token-binding -o json 2>/dev/null \
        | grep -q '"APP_INTERNALTOKEN_MODE"[[:space:]]*:[[:space:]]*"DUAL_ACCEPT"'; then
        offenders="$offenders internal-token-binding=DUAL_ACCEPT"
    fi
    if [ -n "$offenders" ]; then
        echo "$TAG 다운스트림이 SIGNED_ONLY 가 아니다:$offenders" >&2
        echo "$TAG   → 이 상태에서는 200 이 '서명 주입' 을 증명하지 못한다(평문도 수용되므로)." >&2
        echo "$TAG   → §7 ③ 의 '평문 주입 0' 은 ④(signed-only 전환) 이후에 이 probe 로 확정한다." >&2
        return 1
    fi
    echo "$TAG 다운스트림 5서비스 SIGNED_ONLY 확인 — 200 을 서명 주입 증거로 쓸 수 있다"
}

gateway_signing_probe() {
    local pods rc=0 ip code
    assert_downstream_signed_only || return 1
    if [ -z "${SMOKE_USER_EMAIL:-}" ] || [ -z "${SMOKE_USER_PASSWORD:-}" ]; then
        echo "$TAG SMOKE_USER_EMAIL/SMOKE_USER_PASSWORD 미설정 — Pod 별 서명 확인 불가" >&2
        return 2
    fi
    local token
    token=$(curl -s --max-time 10 -X POST "${GW_URL:?GW_URL 필요}/api/v1/auth/login" \
              -H 'Content-Type: application/json' \
              -d "{\"email\":\"${SMOKE_USER_EMAIL}\",\"password\":\"${SMOKE_USER_PASSWORD}\"}" 2>/dev/null \
            | sed -n 's/.*"accessToken"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
    [ -n "$token" ] || { echo "$TAG 로그인 실패 — 서명 확인 불가" >&2; return 2; }

    pods=$(kubectl -n "$NAMESPACE" get pods -l app=gateway -o jsonpath='{range .items[*]}{.metadata.name} {.status.podIP}{"\n"}{end}')
    [ -n "$pods" ] || { echo "$TAG gateway Pod 가 없다" >&2; return 1; }

    while read -r pname ip; do
        [ -n "$ip" ] || continue
        # Pod IP 로 직접 — Service 경유는 어느 Pod 가 받았는지 보증하지 못한다.
        # VPC 내부(loadgen VM 등)에서 실행해야 한다.
        code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 \
                 -H "Authorization: Bearer $token" "http://$ip:8080/api/v1/orders" 2>/dev/null || true)
        if [ "$code" = "200" ]; then
            echo "$TAG   $pname($ip): 200 — 유효한 X-Internal-Auth 주입 확인(평문 주입 아님)"
        else
            echo "$TAG   $pname($ip): $code — 서명 주입 미확인(구 이미지가 평문을 주입 중일 수 있다)" >&2
            rc=1
        fi
    done <<<"$pods"
    return "$rc"
}

# ── main ──
deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
if [ -n "$WORKLOADS" ]; then
    IFS=',' read -ra WL <<<"$WORKLOADS"
    while :; do
        all_ok=1
        echo "$TAG 수렴 확인 중 ($(date -u +%T)) …"
        for wl in "${WL[@]}"; do
            if out=$(converged "$wl"); then
                echo "$TAG   $out"
                old_replicasets_drained "$wl" || all_ok=0
                if [ -n "$EXPECT_IMAGE" ]; then
                    if digest_out=$(pods_match_digest "$wl" "$EXPECT_IMAGE"); then
                        echo "$TAG $digest_out"
                    else
                        echo "$TAG $digest_out" >&2
                        all_ok=0
                    fi
                fi
            else
                echo "$TAG   $out" >&2
                all_ok=0
            fi
        done
        [ "$all_ok" -eq 1 ] && break
        if [ "$(date +%s)" -ge "$deadline" ]; then
            echo "$TAG FATAL 수렴 timeout(${TIMEOUT_SECONDS}s) — 다음 단계로 진행 금지" >&2
            exit 1
        fi
        sleep "$POLL_SECONDS"
    done
    echo "$TAG 전량 수렴 확인 — 다음 단계 진입 가능"
fi

if [ "$SIGNING_PROBE" -eq 1 ]; then
    echo "$TAG gateway Pod 별 서명 주입 확인 …"
    gateway_signing_probe || {
        echo "$TAG FATAL 서명 주입 미수렴 — 평문 주입 0 을 주장할 수 없다" >&2
        exit 1
    }
    echo "$TAG 전 Pod 서명 주입 확인 (평문 주입 0)"
fi

if [ -z "$WORKLOADS" ] && [ "$SIGNING_PROBE" -eq 0 ]; then
    echo "$TAG --workloads 또는 --gateway-signing-probe 중 하나는 필요하다" >&2
    exit 2
fi

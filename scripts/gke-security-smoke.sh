#!/usr/bin/env bash
# gke-security-smoke.sh — PR3c 실 클러스터 보안 smoke (ADR-0013 D3 · 구현 ③ PR3c)
#
# 목적: NetworkPolicy enforcement·직접경로 차단·spoof 제거·scrape 예외를 **실 GKE 클러스터**에서 증명한다.
#       렌더 성공은 증적이 아니다 — enforcement 는 CNI 의존이라 매니페스트만으로는 검증되지 않는다.
#
# 두 모드:
#   --barrier  header-trust 이미지 rollout **전에** 실행하는 안전 게이트(§8-3). 각 검사가 기대와
#              불일치하면 exit 1 — 통과 전에는 rollout 하면 안 된다(spoof 창 방지, review #1/#2).
#   (default)  barrier → canary probe(공개/보호/spoof/webhook) → **실제 결과를 증적 파일에 기록**.
#              하나라도 실패하면 성공 증적을 만들지 않고 exit 1(review #4). digest 고정 배포·오류율
#              임계·refresh/logout 혼재 검증은 운영자 주도 절차(§8-4)라 이 스크립트가 대신하지 않으며,
#              증적에 "미수행" 으로 명시한다(렌더/부분실행을 완료로 위장하지 않는다).
#
# 필수 환경변수:
#   CLUSTER          GKE 클러스터 이름(enforcement 조회)
#   GW_URL           gateway 진입점 (예: http://<internal-lb>:8080)
#   DIRECT_ENDPOINTS 이전 직접 경로(공백 구분, host:port) — **비우면 barrier 실패**(vacuous-green 방지).
#                    예: "10.0.0.11:8080 10.0.0.12:8080 ..." (제거됐으니 전부 도달 불가여야 함)
# 선택:
#   NAMESPACE(기본 peekcart) · ZONE/REGION · PROM_URL(Prometheus) · EVIDENCE_DIR(기본 docs/progress/evidence)
#   EXPECTED_DIRECT_COUNT (기본 5) — DIRECT_ENDPOINTS 개수 검증
#
# Exit: 0 전부 통과 / 1 검사 실패 / 2 전제 미충족(도구/환경변수)

set -euo pipefail

TAG="[GKE-SMOKE]"
NAMESPACE="${NAMESPACE:-peekcart}"
EVIDENCE_DIR="${EVIDENCE_DIR:-docs/progress/evidence}"
EXPECTED_DIRECT_COUNT="${EXPECTED_DIRECT_COUNT:-5}"

need() { command -v "$1" >/dev/null 2>&1 || { echo "$TAG '$1' 필요" >&2; exit 2; }; }
req_env() { [ -n "${!1:-}" ] || { echo "$TAG 환경변수 $1 필요" >&2; exit 2; }; }

need kubectl
need curl
req_env GW_URL

# HTTP 상태코드만 단일값으로 반환. 연결 실패면 curl 이 이미 "000" 을 출력하므로 fallback 을 덧붙이지
# 않는다(review #1/#2: `|| echo 000` 이 "000000" 을 만들어 판정을 뒤집었다).
http_code() { curl -s -o /dev/null -w '%{http_code}' --max-time "${2:-5}" "$1" 2>/dev/null || true; }

# ── (1) enforcement 활성 hard-fail — Dataplane V2 또는 networkPolicy.enabled (단일 계약, review #4) ──
check_enforcement() {
    req_env CLUSTER
    need gcloud
    local loc_flag=()
    [ -n "${ZONE:-}" ] && loc_flag=(--zone "$ZONE")
    [ -n "${REGION:-}" ] && loc_flag=(--region "$REGION")
    local dp nm
    dp=$(gcloud container clusters describe "$CLUSTER" "${loc_flag[@]}" \
            --format='value(networkPolicy.enabled)' 2>/dev/null || echo "")
    nm=$(gcloud container clusters describe "$CLUSTER" "${loc_flag[@]}" \
            --format='value(networkConfig.datapathProvider)' 2>/dev/null || echo "")
    if [ "$dp" = "True" ] || [ "$nm" = "ADVANCED_DATAPATH" ]; then
        echo "$TAG (1) enforcement 활성 OK (networkPolicy.enabled=$dp datapath=$nm)"
    else
        echo "$TAG FATAL (1) NetworkPolicy enforcement OFF (enabled=$dp datapath=$nm) — 정책이 조용히 무시된다" >&2
        return 1
    fi
}

# ── (2) non-gateway Pod → backend 8080 차단 (정책 양성) ──
# kubectl run 자체 실패(RBAC·image·context)와 curl 결과를 분리한다(review #1). marker 로 curl 실행을
# 확인하고, marker 부재면 "검증 불가 → barrier 실패" 로 처리한다(로그 없는 실패를 차단 성공으로 오판 금지).
check_non_gateway_blocked() {
    local out marker code
    out=$(kubectl -n "$NAMESPACE" run np-probe-$$ --image=curlimages/curl --restart=Never --rm -i --quiet \
             --command -- sh -c \
             'printf "MARK:%s" "$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://order-service:8080/actuator/health 2>/dev/null)"' \
             2>/dev/null || true)
    marker=$(printf '%s' "$out" | grep -o 'MARK:[0-9]*' | head -n1 || true)
    if [ -z "$marker" ]; then
        echo "$TAG FATAL (2) probe Pod 실행 실패(marker 없음) — 차단 여부를 검증할 수 없다(RBAC/image/context 확인)" >&2
        return 1
    fi
    code="${marker#MARK:}"
    if [ "$code" = "000" ]; then
        echo "$TAG (2) non-gateway → order-service 차단 OK (연결 실패 000)"
    else
        echo "$TAG FATAL (2) non-gateway Pod 가 order-service 에 도달(HTTP $code) — 정책 미차단" >&2
        return 1
    fi
}

# ── (3) gateway 경유 공개 경로 200 ──
check_gateway_public() {
    local code; code=$(http_code "$GW_URL/api/v1/products")
    [ "$code" = "200" ] && { echo "$TAG (3) gateway 공개 경로 200 OK"; return 0; }
    echo "$TAG FATAL (3) gateway 공개 경로 code=$code (기대 200)" >&2; return 1
}

# ── (4) Prometheus target up ≥ 5 (monitoring 예외 동작) ──
check_prometheus_up() {
    req_env PROM_URL
    need jq
    local up
    up=$(curl -s --max-time 5 "$PROM_URL/api/v1/query?query=up%7Bnamespace%3D%22$NAMESPACE%22%7D" \
            | jq '[.data.result[].value[1]|tonumber]|add // 0' 2>/dev/null || echo 0)
    if [ "${up%.*}" -ge 5 ] 2>/dev/null; then
        echo "$TAG (4) Prometheus scrape up=$up OK (monitoring 예외 동작)"
    else
        echo "$TAG FATAL (4) peekcart scrape target up=$up (<5) — monitoring 예외 미동작" >&2
        return 1
    fi
}

# ── (5) 직접 경로 도달 불가 (직접 노출 제거 확인) ──
# DIRECT_ENDPOINTS 를 필수로 요구하고 개수도 검증한다(review #2: 비어있으면 0회 실행 후 PASS = vacuous).
# HTTP 응답이 오면(2xx/4xx/5xx 무관) 표면이 살아있는 것 → 실패. 오직 연결 실패(000)만 도달불가로 인정.
check_direct_unreachable() {
    req_env DIRECT_ENDPOINTS
    local eps=($DIRECT_ENDPOINTS) failed=0 ep code
    if [ "${#eps[@]}" -ne "$EXPECTED_DIRECT_COUNT" ]; then
        echo "$TAG FATAL (5) DIRECT_ENDPOINTS 개수 ${#eps[@]} != 기대 $EXPECTED_DIRECT_COUNT — 직접 경로 목록 불완전" >&2
        return 1
    fi
    for ep in "${eps[@]}"; do
        code=$(http_code "http://$ep/actuator/health" 3)
        if [ "$code" = "000" ]; then
            echo "$TAG (5) 직접 경로 $ep 도달불가 OK (연결 실패)"
        else
            echo "$TAG FATAL (5) 직접 경로 $ep 가 응답(HTTP $code) — 표면이 살아있다(ClusterIP 환원/정책 미반영)" >&2
            failed=1
        fi
    done
    return "$failed"
}

run_barrier() {
    local rc=0
    check_enforcement         || rc=1
    check_non_gateway_blocked || rc=1
    check_gateway_public      || rc=1
    check_prometheus_up       || rc=1
    check_direct_unreachable  || rc=1
    if [ "$rc" -ne 0 ]; then
        echo "$TAG barrier 실패 — header-trust rollout 금지(§8-3, 안전 순서 ¬b)" >&2
        return 1
    fi
    echo "$TAG barrier PASS — rollout 진행 가능"
}

# ── canary probe (공개/보호/spoof) — 전환 증적 ──
run_canary() {
    local rc=0 pub prot spoof
    pub=$(http_code "$GW_URL/api/v1/products")
    prot=$(http_code "$GW_URL/api/v1/orders")
    spoof=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
              -H 'X-User-Id: 999' -H 'X-User-Role: ADMIN' "$GW_URL/api/v1/orders" 2>/dev/null || true)
    CANARY_RESULT="공개(/products)=$pub 보호(/orders 무토큰)=$prot spoof(X-User-*)=$spoof"
    [ "$pub" = "200" ]  && echo "$TAG canary 공개 200 OK"       || { echo "$TAG FAIL 공개=$pub" >&2; rc=1; }
    [ "$prot" = "401" ] && echo "$TAG canary 보호 무토큰 401 OK" || { echo "$TAG FAIL 보호=$prot" >&2; rc=1; }
    [ "$spoof" = "401" ] && echo "$TAG canary spoof strip→401 OK" || { echo "$TAG FAIL spoof=$spoof" >&2; rc=1; }
    return "$rc"
}

case "${1:-}" in
    --barrier)
        run_barrier
        ;;
    *)
        # default: barrier + canary 를 실제 실행하고 결과를 증적에 기록. 실패 시 성공 증적을 만들지 않는다.
        ts=$(date -u +%Y%m%d-%H%M)
        mkdir -p "$EVIDENCE_DIR"
        f="$EVIDENCE_DIR/pr3c-gke-smoke-$ts.md"
        log="$(mktemp)"
        rc=0
        { run_barrier && CANARY_RESULT="" && run_canary; } 2>&1 | tee "$log" || rc=1
        # tee 는 파이프 첫 명령의 rc 를 가리므로 PIPESTATUS 로 실제 판정.
        [ "${PIPESTATUS[0]:-1}" -eq 0 ] || rc=1
        if [ "$rc" -ne 0 ]; then
            echo "$TAG smoke 실패 — 성공 증적을 생성하지 않는다(§5 렌더-only 대체 금지)" >&2
            rm -f "$log"
            exit 1
        fi
        {
            echo "# PR3c GKE 보안 smoke 증적 — $(date -u +%FT%TZ)"
            echo
            echo "- CLUSTER: ${CLUSTER:-?}  NAMESPACE: $NAMESPACE  GW_URL: $GW_URL"
            echo "- 수행: barrier(enforcement·non-gateway 차단·공개·scrape·직접경로 도달불가) + canary(공개/보호/spoof)"
            echo "- canary: ${CANARY_RESULT:-n/a}"
            echo "- **미수행(운영자 주도, §8-4)**: digest 고정 배포·오류율 임계·payment webhook·refresh/logout 혼재 검증"
            echo
            echo '```'
            cat "$log"
            echo '```'
        } >"$f"
        rm -f "$log"
        echo "$TAG 증적: $f"
        echo "$TAG cleanup: bash loadtest/cleanup.sh (orphan PD/IP 확인)"
        ;;
esac

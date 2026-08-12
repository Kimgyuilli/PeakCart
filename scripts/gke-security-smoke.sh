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
# canary/crypto 결과는 `... | tee` 파이프라인(서브셸) 안에서 산출되므로 변수로는 부모 셸에 못 돌아온다.
# 파일 경유가 유일하게 확실한 전달 수단이다(PR3c 증적 헤더가 늘 n/a 였던 원인).
RESULT_FILE="$(mktemp -t gke-smoke-canary.XXXXXX)"
CRYPTO_RESULT_FILE="$(mktemp -t gke-smoke-crypto.XXXXXX)"
trap 'rm -f "$RESULT_FILE" "$CRYPTO_RESULT_FILE"' EXIT
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

# ── barrier ② signed-only crypto barrier (ADR-0017 · 구현 ③ PR3d P10 ②) ──
#
# barrier ①(network preflight, run_barrier)이 "직접 경로가 막혔다" 를 보이는 반면, 여기서는
# "게이트웨이를 통해도 서명 없이는 못 들어간다" 를 본다. 두 방벽은 AND 로 걸린다(defense-in-depth).
#
# **직접경로 Bearer 거부는 gateway Pod 안에서 때린다**(계획 P10 ② 필수 항목):
#   클러스터 *밖*에서는 NetworkPolicy 때문에 서비스에 닿을 수 없어 이 검사를 할 수 없다. 그러나
#   gateway Pod 는 정책상 허용된 유일한 peer 이므로, 거기서 유효한 사용자 access token 을
#   `Authorization: Bearer` 로 서비스에 직접 보내면 "서비스가 사용자 토큰을 자기 힘으로 인증하는가"
#   를 실제로 때릴 수 있다. 401 이어야 한다 — 200 이면 사용자 토큰 verifier 가 되살아난 회귀이고,
#   그 경우 Gateway 를 우회한 인증이 성립한다(ADR-0014 D2-c exit 파기).
#
# 정상 서명 200 **양성 대조군**이 필수다(PR3c 검사(5) 3상태 교훈): 전부 401 인 상태 — 예컨대 gateway 가
# 아예 죽어 모든 요청이 실패하는 경우 — 에서도 "위조가 401" 은 참이 되어 vacuous-green 이 된다.
run_crypto_barrier() {
    local rc=0 forged_code plain_code ok_code token
    # (1) 위조 내부 토큰 — 서명이 붙은 것처럼 보이는 임의 JWT. gateway 는 외부 유입을 strip 하므로
    #     "무토큰 요청" 으로 취급돼 401 이어야 한다. 200 이면 외부 주입이 신뢰된 것이다.
    local forged="eyJhbGciOiJSUzI1NiIsImtpZCI6ImZvcmdlZCJ9.eyJzdWIiOiI5OTkiLCJyb2xlIjoiQURNSU4ifQ.ZmFrZQ"
    forged_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
                    -H "X-Internal-Auth: $forged" "$GW_URL/api/v1/orders" 2>/dev/null || true)
    if [ "$forged_code" = "401" ]; then
        echo "$TAG (②-1) 위조 X-Internal-Auth 401 OK"
    else
        echo "$TAG FATAL (②-1) 위조 X-Internal-Auth 가 $forged_code — 외부 주입이 신뢰된다" >&2
        rc=1
    fi

    # (2) 평문 X-User-* 직접 주입 — strip 대상. 인증 주체가 서지 않아 401.
    plain_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
                   -H 'X-User-Id: 999' -H 'X-User-Role: ADMIN' \
                   -H 'X-User-Family-Id: f-999' "$GW_URL/api/v1/orders" 2>/dev/null || true)
    if [ "$plain_code" = "401" ]; then
        echo "$TAG (②-2) 평문 X-User-* 무시→401 OK"
    else
        echo "$TAG FATAL (②-2) 평문 X-User-* 가 $plain_code — header-trust 잔재" >&2
        rc=1
    fi

    # (3) 양성 대조군 — 정상 로그인 토큰으로 보호 경로 200. 이게 없으면 위 401 들이 무의미하다.
    #     자격증명은 운영자가 SMOKE_USER_EMAIL/SMOKE_USER_PASSWORD 로 주입한다.
    if [ -z "${SMOKE_USER_EMAIL:-}" ] || [ -z "${SMOKE_USER_PASSWORD:-}" ]; then
        echo "$TAG FATAL (②-3) SMOKE_USER_EMAIL/SMOKE_USER_PASSWORD 미설정 — 양성 대조군 없이" \
             "위조 401 을 주장할 수 없다(vacuous-negative)" >&2
        return 1
    fi
    token=$(curl -s --max-time 10 -X POST "$GW_URL/api/v1/auth/login" \
              -H 'Content-Type: application/json' \
              -d "{\"email\":\"${SMOKE_USER_EMAIL}\",\"password\":\"${SMOKE_USER_PASSWORD}\"}" 2>/dev/null \
            | sed -n 's/.*"accessToken"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
    if [ -z "$token" ]; then
        echo "$TAG FATAL (②-3) 로그인 실패 — 양성 대조군을 만들 수 없다" >&2
        return 1
    fi
    ok_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
                -H "Authorization: Bearer $token" "$GW_URL/api/v1/orders" 2>/dev/null || true)
    if [ "$ok_code" = "200" ]; then
        echo "$TAG (②-3) 정상 로그인 토큰 200 OK (양성 대조군 — gateway 서명 주입 경로 동작)"
    else
        echo "$TAG FATAL (②-3) 정상 토큰이 $ok_code — 서명 주입 경로가 깨졌다" >&2
        rc=1
    fi

    # (4) 직접경로 Bearer 거부 — gateway Pod 에서 order-service 로 직접. 유효한 사용자 토큰이므로
    #     "토큰이 깨져서 401" 이 아니라 "서비스가 사용자 토큰을 인증 근거로 쓰지 않아서 401" 이다
    #     (PR3d-a 에서 깨진 문자열을 써 vacuous-negative 를 만들었던 실수의 클러스터 판).
    local direct_code gw_pod probe_out marker
    gw_pod=$(kubectl -n "$NAMESPACE" get pods -l app=gateway \
               -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
    if [ -z "$gw_pod" ]; then
        echo "$TAG FATAL (②-4) gateway Pod 를 찾을 수 없다 — 직접경로 Bearer 검사를 수행 못 함" >&2
        return 1
    fi
    # marker 로 curl 실제 실행을 확인한다 — exec 실패(RBAC/컨테이너에 curl 부재)를 차단 성공으로
    # 오판하면 안 된다(검사 (2) 와 동일 규약).
    probe_out=$(kubectl -n "$NAMESPACE" exec "$gw_pod" -- sh -c \
                  "printf 'MARK:%s' \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
                     -H 'Authorization: Bearer $token' http://order-service:8080/api/v1/orders 2>/dev/null)\"" \
                2>/dev/null || true)
    marker=$(printf '%s' "$probe_out" | grep -o 'MARK:[0-9]*' | head -n1 || true)
    if [ -z "$marker" ]; then
        echo "$TAG FATAL (②-4) gateway Pod probe 실행 실패(marker 없음) — 직접경로 거부를 검증할 수 없다" >&2
        return 1
    fi
    direct_code="${marker#MARK:}"
    if [ "$direct_code" = "401" ]; then
        echo "$TAG (②-4) 직접경로 Bearer(유효 사용자 토큰) 401 OK — 서비스 측 사용자 verifier 부재 확인"
    else
        echo "$TAG FATAL (②-4) 직접경로 Bearer 가 $direct_code — 사용자 토큰 verifier 부활(Gateway 우회 가능)" >&2
        rc=1
    fi

    CRYPTO_RESULT="위조=$forged_code 평문=$plain_code 정상=$ok_code 직접경로Bearer=$direct_code"
    printf '%s\n' "$CRYPTO_RESULT" >"$CRYPTO_RESULT_FILE"

    if [ "$rc" -ne 0 ]; then
        echo "$TAG crypto barrier 실패 — signed-only 전환을 완료로 기록하지 않는다" >&2
        return 1
    fi
    echo "$TAG crypto barrier PASS"
}

# ── canary probe (공개/보호/spoof) — 전환 증적 ──
run_canary() {
    local rc=0 pub prot spoof
    pub=$(http_code "$GW_URL/api/v1/products")
    prot=$(http_code "$GW_URL/api/v1/orders")
    spoof=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
              -H 'X-User-Id: 999' -H 'X-User-Role: ADMIN' "$GW_URL/api/v1/orders" 2>/dev/null || true)
    CANARY_RESULT="공개(/products)=$pub 보호(/orders 무토큰)=$prot spoof(X-User-*)=$spoof"
    # 이 함수는 `... | tee` 파이프라인 안(서브셸)에서 호출된다 — 변수 대입은 부모 셸로 전파되지
    # 않아 증적 헤더가 항상 `n/a` 였다(PR3c 발견분). 결과는 파일로 넘긴다.
    printf '%s\n' "$CANARY_RESULT" >"$RESULT_FILE"
    [ "$pub" = "200" ]  && echo "$TAG canary 공개 200 OK"       || { echo "$TAG FAIL 공개=$pub" >&2; rc=1; }
    [ "$prot" = "401" ] && echo "$TAG canary 보호 무토큰 401 OK" || { echo "$TAG FAIL 보호=$prot" >&2; rc=1; }
    [ "$spoof" = "401" ] && echo "$TAG canary spoof strip→401 OK" || { echo "$TAG FAIL spoof=$spoof" >&2; rc=1; }
    return "$rc"
}

case "${1:-}" in
    --barrier)
        # ① network preflight 만 — signed-only 전환 **전**에 도는 hard gate(§7 ①).
        run_barrier
        ;;
    --crypto-barrier)
        # ② signed-only crypto barrier 만 — 전환 **후**에 돈다(§7 ⑤).
        run_crypto_barrier
        ;;
    *)
        # default: barrier + canary 를 실제 실행하고 결과를 증적에 기록. 실패 시 성공 증적을 만들지 않는다.
        ts=$(date -u +%Y%m%d-%H%M)
        mkdir -p "$EVIDENCE_DIR"
        f="$EVIDENCE_DIR/pr3c-gke-smoke-$ts.md"
        log="$(mktemp)"
        rc=0
        : >"$RESULT_FILE"
        { run_barrier && run_crypto_barrier && run_canary; } 2>&1 | tee "$log" || rc=1
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
            echo "- 수행: barrier ①(enforcement·non-gateway 차단·공개·scrape·직접경로 도달불가)"
            echo "        + barrier ②(위조 X-Internal-Auth·평문 X-User-*·정상 서명 양성 대조군)"
            echo "        + canary(공개/보호/spoof)"
            echo "- barrier ②: $(cat "$CRYPTO_RESULT_FILE" 2>/dev/null || echo n/a)"
            echo "- canary: $(cat "$RESULT_FILE" 2>/dev/null || echo n/a)"
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

#!/usr/bin/env bash
# saga-e2e-smoke — cross-service saga E2E 실행자 (계획 P5~P11 · P16 · P19)
#
# 스택을 띄우고 → readiness 를 확인하고 → 시나리오를 돌리고 → 증적을 남기고 → 내린다.
# 시나리오 본체는 runner 컨테이너 안의 saga_e2e.py 다(internal 네트워크 안에서만 DB·Kafka·앱에 닿는다).
#
# 사용:
#   bash scripts/saga-e2e-smoke.sh                 # 전체 (readiness + 시나리오 4종)
#   bash scripts/saga-e2e-smoke.sh --scenarios a,b # 일부만
#   bash scripts/saga-e2e-smoke.sh --keep          # 실패 조사용으로 스택 유지
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

COMPOSE_FILE="docker-compose.e2e.yml"
SCENARIOS="a,b,c,d"
KEEP=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenarios) SCENARIOS="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done

export E2E_RUN_ID="${E2E_RUN_ID:-local-$(date +%s)-$$}"
export E2E_COMMIT_SHA="${E2E_COMMIT_SHA:-$(git rev-parse HEAD 2>/dev/null || echo unknown)}"
export PEEKCART_IMAGE_TAG="${PEEKCART_IMAGE_TAG:-ci}"
PROJECT="${E2E_PROJECT:-e2e-${E2E_RUN_ID}}"

OUT_DIR=".cache/e2e/${E2E_RUN_ID}"
mkdir -p "$OUT_DIR"

dc() { docker compose -f "$COMPOSE_FILE" -p "$PROJECT" "$@"; }

collect() {
  # 실패해도 증적은 남긴다 — 실패한 실행의 로그가 가장 필요하다.
  dc logs --no-color >"$OUT_DIR/compose.log" 2>&1 || true
  echo "증적: $OUT_DIR"
}

teardown() {
  local rc=$?
  collect
  if [[ $KEEP -eq 1 ]]; then
    echo "[--keep] 스택을 유지한다. 정리: docker compose -f $COMPOSE_FILE -p $PROJECT down -v --remove-orphans"
  else
    dc down -v --remove-orphans >/dev/null 2>&1 || true
  fi
  exit $rc
}
trap teardown EXIT

# volume 재사용 차단 (계획 P4) — flyway 검사만으로는 warm reuse 를 구별하지 못하므로
# 기동 **전에** 막는다. marker 대조는 readiness 에서 한 번 더 한다.
for v in mysql-data redis-data kafka-data; do
  if docker volume inspect "${PROJECT}_${v}" >/dev/null 2>&1; then
    echo "::error::[saga-e2e] volume ${PROJECT}_${v} 가 이미 있다 — cold start 가 아니다" >&2
    exit 1
  fi
done

# readiness 가 쓸 업무 group 목록을 DlqTopology 에서 유도한다(정본 복제 금지).
bash scripts/kafka-subscription-contract-lint.sh --emit-groups | sort -u > scripts/e2e/business-groups.txt

echo "== 스택 기동 (project=$PROJECT, run_id=$E2E_RUN_ID) =="
dc build runner >/dev/null
dc up -d --wait --wait-timeout 300

echo "== readiness =="
dc run --rm -T -e E2E_OUT_DIR="/work/out/${E2E_RUN_ID}" runner \
  -c "python3 /work/e2e/saga_e2e.py readiness" | tee "$OUT_DIR/readiness.json"

IFS=',' read -ra LIST <<< "$SCENARIOS"
for s in "${LIST[@]}"; do
  echo "== 시나리오 $s =="
  dc run --rm -T -e E2E_OUT_DIR="/work/out/${E2E_RUN_ID}" runner \
    -c "python3 /work/e2e/saga_e2e.py scenario $s"
done

echo "== 완료 =="

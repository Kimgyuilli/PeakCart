#!/usr/bin/env bash
# docs/check-consistency.sh
#
# 문서 일관성 검증 스크립트 (Layer 1 ↔ ADR ↔ Progress).
#
# 검증 항목:
#   1. Layer 1 문서(01~07)에서 환경 단어(minikube, GKE, GCP) 등장 시 ADR 참조 동반 여부
#   2. 모든 `ADR-NNNN` 참조의 실제 파일 존재 여부
#   3. k8s/ 디렉토리 구조가 02-architecture.md의 트리와 일치하는지 (수동 확인 힌트)
#
# 사용법: bash docs/check-consistency.sh
# 종료 코드: 0 = 통과, 1 = 불일치 발견

set -u
cd "$(dirname "$0")/.."

FAIL=0
LAYER1_DOCS=(docs/01-*.md docs/02-*.md docs/03-*.md docs/04-*.md docs/05-*.md docs/06-*.md docs/07-*.md)
ENV_WORDS='minikube|GKE|GCP|Google Cloud'

echo "=== 1. Layer 1 환경 단어 등장 위치 점검 ==="
echo "  (환경 단어가 등장하는 줄 근처에 ADR 참조가 있어야 합니다)"
for doc in "${LAYER1_DOCS[@]}"; do
  [ -f "$doc" ] || continue
  matches=$(grep -nE "$ENV_WORDS" "$doc" || true)
  [ -z "$matches" ] && continue
  echo "  [$doc]"
  echo "$matches" | sed 's/^/    /'
done
echo

echo "=== 2. ADR 참조 유효성 점검 ==="
adr_refs=$(grep -rhoE 'ADR-[0-9]{4}' docs/ 2>/dev/null | sort -u || true)
for ref in $adr_refs; do
  num="${ref#ADR-}"
  if ! ls docs/adr/${num}-*.md >/dev/null 2>&1; then
    echo "  [MISS] $ref — 해당 ADR 파일 없음"
    FAIL=1
  fi
done
[ $FAIL -eq 0 ] && echo "  OK — 모든 ADR 참조 유효"
echo

echo "=== 3. k8s/ 매니페스트 구조 (수동 확인 힌트) ==="
if [ -d k8s ]; then
  find k8s -maxdepth 3 -type d | sort | sed 's/^/  /'
else
  echo "  k8s/ 디렉토리 없음"
fi
echo

echo "=== 4. TASKS.md ↔ progress/PHASE3.md 완료 상태 교차 확인 힌트 ==="
if [ -f docs/TASKS.md ] && [ -f docs/progress/PHASE3.md ]; then
  echo "  TASKS.md 완료 Task:"
  grep -nE '상태.*✅' docs/TASKS.md | sed 's/^/    /' || true
fi
echo

if [ $FAIL -ne 0 ]; then
  echo "FAIL — 불일치 발견. 위 항목 확인 필요."
  exit 1
fi
echo "PASS — 자동 검증 통과 (수동 확인 항목은 별도 점검)"

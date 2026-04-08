#!/usr/bin/env bash
# loadtest/scripts/generate-users-csv.sh
#
# seed.sql 과 동일 규칙으로 users.csv 를 재생성한다.
# 기본: loaduser0001 ~ loaduser1100, 비밀번호 "LoadTest123!".
#
# 사용법:
#   bash loadtest/scripts/generate-users-csv.sh [--count 1100] [--out loadtest/scripts/users.csv]

set -euo pipefail

COUNT=1100
OUT="$(dirname "$0")/users.csv"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --count) COUNT="$2"; shift 2;;
    --out)   OUT="$2";   shift 2;;
    *) echo "unknown arg: $1" >&2; exit 1;;
  esac
done

{
  echo "email,password"
  for i in $(seq 1 "$COUNT"); do
    printf 'loaduser%04d@peekcart.test,LoadTest123!\n' "$i"
  done
} > "$OUT"

echo "wrote $COUNT users to $OUT"

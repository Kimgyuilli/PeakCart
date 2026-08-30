#!/usr/bin/env bash
# D5-V2 (MeterFilter / MeterRegistryCustomizer 단일 owner) 검출기 self-test.
#
# 왜 필요한가: 이 검사는 "주석 안의 식별자 언급은 정상, 코드의 재선언은 위반" 을 가려야 한다.
# 줄 단위 정규식으로는 이 경계가 반복해서 깨졌다 (구현 ⑤ diff 리뷰 2R·3R·4R 에서 연속 발견):
#   - 라인 통째 제외 → `/* note */ @Bean MeterFilter f()` 우회
#   - sed 구분자 `:` 충돌 → 치환이 통째로 죽어 검출 0건
#   - 여러 줄 주석 종료 줄 `*/ @Bean MeterFilter f()` 우회
#   - 문자열 안의 `//` 부터 잘려 우회
# 지금은 블록주석/문자열/문자/텍스트블록 상태를 추적하는 lexer 를 쓴다. 이 파일이 그 계약이다.
#
# Exit: 0 = 전 케이스 기대대로, 1 = 하나라도 어긋남

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

TARGET="product-service/src/main/java/com/peekcart/global/config/CacheConfig.java"
ANCHOR='    @Bean'
BACKUP="$(mktemp)"
STDERR_LOG="$(mktemp)"
cp "$TARGET" "$BACKUP"
trap 'cp "$BACKUP" "$TARGET"; rm -f "$BACKUP" "$STDERR_LOG"' EXIT

DECL='public org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer<io.micrometer.core.instrument.MeterRegistry> selfTestRogue() { return r -> {}; }'
FAILURES=0

inject () {
    cp "$BACKUP" "$TARGET"
    python3 - "$1" "$TARGET" "$ANCHOR" <<'PY'
import sys
snippet, path, anchor = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding="utf-8").read()
open(path, "w", encoding="utf-8").write(s.replace(anchor, snippet + "\n\n" + anchor, 1))
PY
}

expect () {   # expect <detected|clean> <label> <snippet>
    local want="$1" label="$2" snippet="$3" rc
    inject "$snippet"
    # 종료 코드를 그대로 본다. `if !` 로 뭉뚱그리면 lexer traceback 이나 preflight 실패(rc=2)가
    # '검출 성공' 으로 위장돼, 검사기가 고장난 상태에서도 위반 케이스가 전부 ok 로 찍힌다.
    bash scripts/observability-ssot-lint.sh >/dev/null 2>"$STDERR_LOG"
    rc=$?
    case "$rc" in
        0) got="clean" ;;
        1) got="detected" ;;
        *) echo "  FAIL  [lint 비정상 종료 rc=$rc] $label"
           sed 's/^/          /' "$STDERR_LOG" >&2
           FAILURES=$((FAILURES + 1))
           return ;;
    esac
    if [[ "$got" == "$want" ]]; then
        echo "  ok    [$want] $label"
    else
        echo "  FAIL  [want=$want got=$got rc=$rc] $label"
        FAILURES=$((FAILURES + 1))
    fi
}

echo "[D5-V2 self-test] 위반은 검출되고, 주석/문자열 언급은 통과해야 한다"

expect detected "평범한 재선언" "    @Bean
    $DECL"

expect detected "한 줄 /* */ 뒤 선언" "    /* local override */ @Bean $DECL"

expect detected "// 주석 뒤 같은 줄 선언" "    @Bean
    public io.micrometer.core.instrument.config.MeterFilter selfTestRogue2() { return null; } // MeterFilter 재선언"

expect detected "여러 줄 주석 종료(*/) 뒤 선언" "    /* 여러 줄
       주석 본문
     */ @Bean $DECL"

expect detected "문자열에 // 가 든 어노테이션 뒤 선언" "    @Deprecated(since = \"http://example.com\")
    @Bean
    $DECL"

expect clean "여러 줄 주석 시작 줄의 식별자 언급" "    /* MeterRegistryCustomizer 를
       여기서 선언하지 않는 이유를 적는다
     */"

expect clean "문자열 리터럴 안의 식별자" "    private static final String SELF_TEST_NOTE = \"MeterFilter 는 공유 모듈 소유\";"

expect clean "javadoc 안의 식별자 언급" "    /** {@code MeterRegistryCustomizer} 는 여기서 선언하지 않는다. */"

# text block: escape 된 따옴표를 종료 구분자로 오인하면 이후 코드가 EOF 까지 문자열로 지워진다.
expect detected "text block 안의 escape 된 따옴표 뒤 선언" '    private static final String TB = """
        escape 된 삼중따옴표: \"""
        """;

    @Bean
    '"$DECL"

expect clean "text block 안의 식별자 언급" '    private static final String TB2 = """
        MeterFilter 는 공유 모듈이 소유한다
        """;'

if [[ $FAILURES -gt 0 ]]; then
    echo "[D5-V2 self-test] 실패 ${FAILURES}건" >&2
    exit 1
fi
echo "[D5-V2 self-test] 전 케이스 통과"

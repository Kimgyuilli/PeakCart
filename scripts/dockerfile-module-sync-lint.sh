#!/usr/bin/env bash
# dockerfile-module-sync-lint — settings.gradle include ↔ Dockerfile COPY 목록 정합 (ADR-0011 §이미지 계약)
#
# 무엇을 막는가:
#   멀티모듈에 모듈을 추가하면 `settings.gradle` 만 고치고 Dockerfile COPY 목록을 잊기 쉽다.
#   그러면 로컬 `./gradlew build` 는 그린인데 이미지 빌드만 "Could not resolve project :<모듈>" 로 깨진다.
#   실제로 PR3d-a(#80) 에서 `:internal-token-contract` 누락으로 이미지 6개가 전부 실패했다.
#
# 왜 Dockerfile 주석으로 부족했는가:
#   기존 Dockerfile 은 "settings.gradle 에 모듈을 추가하면 COPY 목록도 동기화하라" 는 주석을 두고 있었다.
#   주석은 검사가 아니다 — 같은 실수가 다시 났다. 그래서 CI 가 강제한다.
#
# 사용: bash scripts/dockerfile-module-sync-lint.sh [--self-test]
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

LINT_PY="$(mktemp -t dockerfile-module-sync-lint.XXXXXX.py)"
trap 'rm -f "$LINT_PY"' EXIT

cat > "$LINT_PY" <<'PYEOF'
import os
import re
import sys

root = sys.argv[1]
settings = os.path.join(root, "settings.gradle")
dockerfile = os.path.join(root, "Dockerfile")

violations = []


def bad(code, message):
    violations.append("[%s] %s" % (code, message))


if not os.path.isfile(settings) or not os.path.isfile(dockerfile):
    print("[DMS-000] settings.gradle 또는 Dockerfile 부재: %s" % root)
    sys.exit(1)

settings_text = open(settings, encoding="utf-8").read()
docker_text = open(dockerfile, encoding="utf-8").read()

# include 'x' / include "x" — 주석 줄은 제외
modules = []
for line in settings_text.splitlines():
    stripped = line.strip()
    if stripped.startswith("//"):
        continue
    m = re.match(r"^include\s+['\"]([^'\"]+)['\"]", stripped)
    if m:
        modules.append(m.group(1).lstrip(":").replace(":", "/"))

if not modules:
    bad("DMS-001", "settings.gradle 에서 include 모듈을 하나도 찾지 못했다 — 검사가 무의미해진다")

copy_lines = [l.strip() for l in docker_text.splitlines() if l.strip().startswith("COPY ")]

for mod in modules:
    has_build = any(re.match(r"^COPY\s+%s/build\.gradle\s" % re.escape(mod), l) for l in copy_lines)
    has_src = any(re.match(r"^COPY\s+%s/\s" % re.escape(mod), l) for l in copy_lines)
    if not has_build:
        bad("DMS-002", "Dockerfile 에 `COPY %s/build.gradle ...` 없음 — 의존 해석 단계에서 모듈이 빠진다" % mod)
    if not has_src:
        bad("DMS-003", "Dockerfile 에 `COPY %s/ ...` 없음 — 설정 단계가 전 모듈을 평가하므로"
                       " project 해석이 실패한다(Could not resolve project :%s)" % (mod, mod))

# 역방향 — Dockerfile 이 settings.gradle 에 없는 모듈을 COPY 하면 삭제 누락이다
copied = set()
for l in copy_lines:
    m = re.match(r"^COPY\s+([A-Za-z0-9._-]+)/(build\.gradle\s|\s)", l)
    if m:
        copied.add(m.group(1))
for mod in sorted(copied - set(modules) - {"gradle"}):
    bad("DMS-004", "Dockerfile 이 settings.gradle 에 없는 모듈 '%s' 를 COPY 한다 — 모듈 제거 시 누락" % mod)

if violations:
    print("dockerfile-module-sync-lint: 위반 %d 건" % len(violations))
    for v in violations:
        print("  " + v)
    sys.exit(1)

print("dockerfile-module-sync-lint: OK (모듈 %d개 · build.gradle/소스 COPY 양방향 정합)" % len(modules))
PYEOF

run_lint() {
    python3 "$LINT_PY" "$1"
}

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

    # mode: full | no-build-copy | no-src-copy | stale-copy
    _fixture() {
        local dir="$1" mode="$2"
        mkdir -p "$dir"
        printf "include 'alpha'\n// include 'commented-out'\ninclude 'beta'\n" > "$dir/settings.gradle"
        {
            echo "FROM eclipse-temurin:17-jdk AS build"
            echo "COPY gradlew settings.gradle build.gradle ./"
            echo "COPY gradle/ gradle/"
            echo "COPY alpha/build.gradle alpha/build.gradle"
            [[ "$mode" != "no-build-copy" ]] && echo "COPY beta/build.gradle beta/build.gradle"
            echo "COPY alpha/ alpha/"
            [[ "$mode" != "no-src-copy" ]] && echo "COPY beta/ beta/"
            [[ "$mode" == "stale-copy" ]] && echo "COPY gamma/ gamma/"
            echo "RUN ./gradlew build"
        } > "$dir/Dockerfile"
    }

    echo "dockerfile-module-sync-lint --self-test"
    _case "정상 배선(현 저장소)" NONE 0 "$(pwd)"
    _fixture "$tmp/c0" full
    _case "정상 배선(픽스처)" NONE 0 "$tmp/c0"
    _fixture "$tmp/c1" no-build-copy
    _case "build.gradle COPY 누락" DMS-002 1 "$tmp/c1"
    _fixture "$tmp/c2" no-src-copy
    _case "소스 디렉토리 COPY 누락 (#80 회귀)" DMS-003 1 "$tmp/c2"
    _fixture "$tmp/c3" stale-copy
    _case "제거된 모듈이 Dockerfile 에 잔존" DMS-004 1 "$tmp/c3"

    rm -rf "$tmp"
    if [[ $failures -gt 0 ]]; then
        echo "self-test 실패 ${failures}건"
        return 1
    fi
    echo "self-test OK (5/5)"
}

if [[ "${1:-}" == "--self-test" ]]; then
    self_test
else
    run_lint "$(pwd)"
fi

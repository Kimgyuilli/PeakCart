#!/usr/bin/env bash
# CI 실행 계약 lint — test 매트릭스 커버리지 · artifact 배치 · 가드 실행 증거.
#
#   settings.gradle 의 include 목록 ↔ ci.yml 의 test 매트릭스 shard→모듈 매핑
#
# 모듈을 추가하고 shard 에 안 넣으면 **그 모듈 테스트가 조용히 사라진다**. 반대로 한 모듈을
# 두 shard 에 넣으면 gate 의 download-artifact(merge-multiple)가 같은 상대 경로에서
# last-writer-wins 로 증적을 덮는다. 둘 다 그린으로 통과하므로 정적으로 막는다.
# (dockerfile-module-sync-lint 가 "모듈 추가 시 COPY 누락" 을 막는 것과 같은 종류다.)
#
# 별도의 "태스크 집합 동등성" 검사는 두지 않는다 — 실측 결과 루트 `build` 와
# `:build` + 전 모듈 `:module:build` 의 dry-run 태스크 집합이 154 == 154 로 동일하다.
# 모듈 집합만 같으면 태스크 집합은 Gradle 의존 그래프가 자동으로 맞춘다.
#
# 사용:
#   ci-test-matrix-lint.sh                     모듈 커버리지 대조
#   ci-test-matrix-lint.sh --merge-artifacts D 충돌 검출 후 shard artifact 를 루트로 병합
#   ci-test-matrix-lint.sh --verify-layout     gate 에서 복원된 artifact 배치 검증
#   ci-test-matrix-lint.sh --verify-guards F   가드 5종이 실제로 실행됐는지 로그 F 로 확인
#   ci-test-matrix-lint.sh --self-test         lint 자신이 위반을 잡는지 (fixture 조작)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 호출 시점에 해석한다 — 로드 시점에 고정하면 self-test 가 fixture 대신 실제 파일을 읽어
# 전 케이스가 통과하는 false-green 이 된다(자체 실측).
settings_path() { echo "${SETTINGS_GRADLE:-$ROOT/settings.gradle}"; }
workflow_path() { echo "${CI_WORKFLOW:-$ROOT/.github/workflows/ci.yml}"; }

# 산출물이 없는 것이 **정상**인 모듈. 정적 목록이어야 "정상 무테스트" 와 "테스트 소실" 이
# 갈린다 — 관측값(XML 0개)으로 판정하면 둘이 같아진다.
ZERO_TEST_MODULES="${ZERO_TEST_MODULES:-peekcart-common-observability internal-token-contract}"

coverage() {
  python3 - "$(settings_path)" "$(workflow_path)" "$ZERO_TEST_MODULES" <<'PY'
import os, re, sys, yaml

settings_path, workflow_path, zero_raw = sys.argv[1], sys.argv[2], sys.argv[3]

declared = re.findall(r"^include\s+'([^']+)'", open(settings_path, encoding="utf-8").read(), re.M)
if not declared:
    print("::error::settings.gradle 에서 include 를 하나도 읽지 못했다 — 파서 고장"); sys.exit(2)

wf = yaml.safe_load(open(workflow_path, encoding="utf-8"))
try:
    include = wf["jobs"]["test"]["strategy"]["matrix"]["include"]
except (KeyError, TypeError):
    print("::error::ci.yml 의 jobs.test.strategy.matrix.include 를 찾지 못했다"); sys.exit(2)
if not include:
    print("::error::test 매트릭스가 비어 있다"); sys.exit(2)

problems, seen_shards, placement = [], set(), {}
for entry in include:
    if not isinstance(entry, dict):
        problems.append("matrix.include 항목이 매핑이 아니다: %r" % (entry,)); continue
    shard = entry.get("shard")
    mods = entry.get("modules")
    if not shard:
        problems.append("shard 이름 없는 항목: %r" % (entry,)); continue
    if shard in seen_shards:
        problems.append("shard 이름 중복: %s" % shard)
    seen_shards.add(shard)
    if not isinstance(mods, str) or not mods.split():
        # 문자열이 정본이다. 리스트로 쓰면 Gradle 명령의 `for m in ${{ matrix.modules }}`
        # 전개와 어긋나므로 타입까지 고정한다.
        problems.append("shard %s: modules 가 비었거나 공백 구분 문자열이 아니다 (%r)" % (shard, mods))
        continue
    for m in mods.split():
        placement.setdefault(m, []).append(shard)

for m in declared:
    where = placement.get(m, [])
    if not where:
        problems.append("모듈 %s 가 어느 shard 에도 없다 — 이 모듈 테스트가 조용히 사라진다" % m)
    elif len(where) > 1:
        problems.append("모듈 %s 가 여러 shard 에 있다(%s) — merge-multiple 이 증적을 덮는다"
                        % (m, ", ".join(where)))

for m in placement:
    if m not in declared:
        problems.append("유령 모듈 %s — settings.gradle 에 include 되지 않았다" % m)

# 무테스트 예외의 드리프트 검사. 예외 모듈에 테스트가 생기면 --verify-layout 이 그 모듈의
# 증적 유실을 영영 못 잡는다 — 예외를 정적으로 두는 대가는 이 검사로 갚는다.
root = os.path.dirname(os.path.abspath(settings_path))
for m in zero_raw.split():
    if m not in declared:
        problems.append("무테스트 예외 %s 가 settings.gradle 에 없다 — 예외 목록이 낡았다" % m)
        continue
    if os.path.isdir(os.path.join(root, m, "src", "test")):
        problems.append("무테스트 예외 %s 에 src/test 가 생겼다 — 예외에서 빼고 "
                        "--verify-layout 의 필수 목록으로 옮겨라" % m)

if problems:
    for p in problems:
        print("::error::[ci-test-matrix-lint] %s" % p)
    sys.exit(1)
print("ci-test-matrix-lint: OK (모듈 %d개, shard %d개)" % (len(declared), len(seen_shards)))
PY
}

# gate 전용 — 복원된 artifact 배치가 온전한가.
# "증적 없음"(계약 위반)과 "배치 유실"(artifact 사고)을 **다른 메시지**로 끝내야
# 원인을 오진하지 않는다.
verify_layout() {
  python3 - "$(settings_path)" "$ZERO_TEST_MODULES" <<'PY'
import re, sys, glob, os

declared = re.findall(r"^include\s+'([^']+)'", open(sys.argv[1], encoding="utf-8").read(), re.M)
zero = set(sys.argv[2].split())

missing = []
for m in declared:
    if m in zero:
        continue
    xml = glob.glob(os.path.join(m, "build", "test-results", "test", "*.xml"))
    if not xml:
        missing.append(m)

if missing:
    print("::error::[ci-test-matrix-lint] 배치/증적 유실 — 산출물이 있어야 할 모듈에 "
          "JUnit XML 이 없다: %s" % ", ".join(missing))
    print("::error::이것은 계약 위반(증적 없음)이 아니라 artifact 업로드/다운로드 사고다.")
    sys.exit(1)
print("ci-test-matrix-lint --verify-layout: OK (필수 %d모듈, 무테스트 허용 %d모듈)"
      % (len(declared) - len(zero), len(zero)))
PY
}

# 가드는 "assertX: <내용> OK (설명)" 형태로 출력한다 — 콜론 바로 뒤에 OK 가 오지 않는
# 가드가 있어(build.gradle:96 은 모듈 목록을 낀다) `^X: OK` 로 잡으면 정상 빌드가 실패한다.
# 가드 목록은 build.gradle 의 `tasks.named('check') { dependsOn ... }` 에서 읽는다.
# 하드코딩하면 check 에 새 가드가 붙었을 때 그 가드의 실행 여부를 아무도 검증하지 않는다.
guard_names() {
  python3 - "${BUILD_GRADLE:-$ROOT/build.gradle}" <<'PY'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"tasks\.named\(\s*['\"]check['\"]\s*\)\s*\{(.*?)\}", src, re.S)
if not m:
    print("::error::[ci-test-matrix-lint] build.gradle 에서 check 의 dependsOn 을 읽지 못했다", file=sys.stderr)
    sys.exit(2)
names = re.findall(r"['\"]([A-Za-z][A-Za-z0-9_]*)['\"]", m.group(1))
if not names:
    print("::error::[ci-test-matrix-lint] check 의 dependsOn 목록이 비었다", file=sys.stderr)
    sys.exit(2)
print(" ".join(names))
PY
}

verify_guards() {
  log="${1:-}"
  if [ -z "$log" ] || [ ! -f "$log" ]; then
    echo "::error::[ci-test-matrix-lint] 가드 로그 부재: ${log:-<인자 없음>} — 실행 여부를 확인할 수 없다"
    return 1
  fi
  guards="$(guard_names)" || return 2
  echo "  가드 정본(build.gradle check dependsOn): $guards"
  missing=0
  for g in $guards; do
    if grep -Eq "^${g}:.*[[:space:]]OK([[:space:]]|\$)" "$log"; then
      echo "  OK   $g"
    else
      echo "::error::[ci-test-matrix-lint] 가드 미실행 또는 출력 부재: $g"
      missing=1
    fi
  done
  [ "$missing" -eq 0 ]
}

# artifact 별 디렉터리(_artifacts/<name>/...)를 루트로 병합한다.
# download-artifact 의 merge-multiple 은 같은 상대 경로에서 last-writer-wins 이므로
# 병합 자체를 우리가 해야 충돌을 **검출**할 수 있다.
merge_artifacts() {
  python3 - "${1:-}" <<'PY'
import os, shutil, sys

src = sys.argv[1]
if not src or not os.path.isdir(src):
    print("::error::[ci-test-matrix-lint] artifact 디렉터리 부재: %r" % src); sys.exit(1)

owner = {}      # 상대경로 -> artifact 이름
collisions = []
for art in sorted(os.listdir(src)):
    base = os.path.join(src, art)
    if not os.path.isdir(base):
        continue
    for dirpath, _, files in os.walk(base):
        for f in files:
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(full, base)
            if rel in owner:
                collisions.append((rel, owner[rel], art))
            else:
                owner[rel] = art

if collisions:
    for rel, a, b in collisions[:20]:
        print("::error::[ci-test-matrix-lint] 배치/증적 유실 — 상대 경로 충돌 %s (%s ↔ %s)" % (rel, a, b))
    print("::error::같은 모듈이 두 shard 에 배치됐거나 artifact 가 변조됐다. "
          "이것은 계약 위반(증적 없음)이 아니라 업로드/병합 사고다.")
    sys.exit(1)

for rel, art in owner.items():
    dst = os.path.join(".", rel)
    os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
    shutil.copy2(os.path.join(src, art, rel), dst)

print("ci-test-matrix-lint --merge-artifacts: OK (파일 %d개, artifact %d개, 충돌 0)"
      % (len(owner), len({v for v in owner.values()})))
PY
}

self_test() {
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' RETURN
  fail=0

  make_wf() {  # $1=include 블록
    cat > "$TMP/ci.yml" <<EOF
jobs:
  test:
    strategy:
      matrix:
        include:
$1
EOF
  }
  printf "include 'alpha'\ninclude 'beta'\n" > "$TMP/settings.gradle"

  # 커버리지 케이스는 예외 목록을 비운다 — 드리프트는 아래 zero-drift 블록이 따로 본다.
  run() { SETTINGS_GRADLE="$TMP/settings.gradle" CI_WORKFLOW="$TMP/ci.yml" ZERO_TEST_MODULES="" \
            coverage >/dev/null 2>&1; }
  check() { # $1=설명 $2=기대(0=통과,1=실패)
    run; got=$?
    if [ "$got" -eq "$2" ]; then echo "  OK   $1"; else echo "  FAIL $1 (기대 $2, 실제 $got)"; fail=1; fi
  }

  make_wf "          - shard: s1
            modules: alpha beta"
  check "정상 — 전 모듈이 정확히 1회" 0

  make_wf "          - shard: s1
            modules: alpha"
  check "누락 — beta 가 어느 shard 에도 없다" 1

  make_wf "          - shard: s1
            modules: alpha beta
          - shard: s2
            modules: beta"
  check "중복 — beta 가 두 shard 에" 1

  make_wf "          - shard: s1
            modules: alpha beta gamma"
  check "유령 — gamma 는 include 되지 않았다" 1

  make_wf "          - shard: s1
            modules: ''
          - shard: s2
            modules: alpha beta"
  check "빈 modules" 1

  make_wf "          - shard: s1
            modules:
              - alpha
              - beta"
  check "타입 오류 — 리스트는 정본이 아니다" 1

  make_wf "          - shard: s1
            modules: alpha
          - shard: s1
            modules: beta"
  check "shard 이름 중복" 1

  cat > "$TMP/ci.yml" <<'EOF'
jobs:
  build:
    runs-on: ubuntu-latest
EOF
  check "test 매트릭스 부재" 2

  # --verify-layout: 필수 모듈에 XML 이 없으면 실패, 무테스트 허용 모듈은 통과
  LDIR="$TMP/layout"; mkdir -p "$LDIR"; ( cd "$LDIR" || exit
    printf "include 'alpha'\ninclude 'zero'\n" > settings.gradle
    mkdir -p alpha/build/test-results/test && : > alpha/build/test-results/test/a.xml
    SETTINGS_GRADLE="$LDIR/settings.gradle" ZERO_TEST_MODULES="zero" \
      bash "$ROOT/scripts/ci-test-matrix-lint.sh" --verify-layout >/dev/null 2>&1
    [ $? -eq 0 ] && echo "  OK   layout — 무테스트 모듈 부재는 정상" || { echo "  FAIL layout 정상 케이스"; exit 1; }
    rm -f alpha/build/test-results/test/a.xml
    SETTINGS_GRADLE="$LDIR/settings.gradle" ZERO_TEST_MODULES="zero" \
      bash "$ROOT/scripts/ci-test-matrix-lint.sh" --verify-layout >/dev/null 2>&1
    [ $? -eq 1 ] && echo "  OK   layout — 필수 모듈 XML 소실 검출" || { echo "  FAIL layout 유실 검출"; exit 1; }
  ) || fail=1

  # ── 무테스트 예외 드리프트 ────────────────────────────────────────────────
  ZDIR="$TMP/zero"; mkdir -p "$ZDIR"
  printf "include 'alpha'\ninclude 'zero'\n" > "$ZDIR/settings.gradle"
  make_wf "          - shard: s1
            modules: alpha zero"
  cp "$TMP/ci.yml" "$ZDIR/ci.yml"
  SETTINGS_GRADLE="$ZDIR/settings.gradle" CI_WORKFLOW="$ZDIR/ci.yml" ZERO_TEST_MODULES="zero" \
    coverage >/dev/null 2>&1
  [ $? -eq 0 ] && echo "  OK   zero-drift — 예외 모듈에 테스트 없음은 정상" || { echo "  FAIL zero-drift 정상"; fail=1; }

  mkdir -p "$ZDIR/zero/src/test"
  SETTINGS_GRADLE="$ZDIR/settings.gradle" CI_WORKFLOW="$ZDIR/ci.yml" ZERO_TEST_MODULES="zero" \
    coverage >/dev/null 2>&1
  [ $? -eq 1 ] && echo "  OK   zero-drift — 예외 모듈에 src/test 가 생기면 검출" || { echo "  FAIL zero-drift 검출"; fail=1; }

  SETTINGS_GRADLE="$ZDIR/settings.gradle" CI_WORKFLOW="$ZDIR/ci.yml" ZERO_TEST_MODULES="nonexistent" \
    coverage >/dev/null 2>&1
  [ $? -eq 1 ] && echo "  OK   zero-drift — 예외 목록이 낡으면 검출" || { echo "  FAIL zero-drift 낡은목록"; fail=1; }

  # ── 가드 로그 확인 (실제 build.gradle 출력 포맷) ────────────────────────────
  GLOG="$TMP/guards.log"
  cat > "$GLOG" <<'REAL'
> Task :assertNoServiceProjectDeps
assertNoServiceProjectDeps: [:common, :peekcart-common-auth] OK (allowlist 외 project 의존 없음)
assertNoDuplicateGlobalFqcn: OK (order·payment classpath 중복 FQCN/JwtProvider 없음)
assertNoOrderProductSourceCoupling: OK (src/main order↔product 상호 참조 없음)
assertGatewayHasNoServletDeps: OK (project 의존 allowlist=[:internal-token-contract] 외 0 · 계약 모듈 무오염 · servlet 아티팩트 유입 없음)
assertNoOrderPaymentSourceCoupling: OK (src/main order↔payment 상호 참조 없음)
REAL
  verify_guards "$GLOG" >/dev/null 2>&1
  [ $? -eq 0 ] && echo "  OK   guards — 실제 출력 5종 매치" || { echo "  FAIL guards 실제포맷 (콜론 뒤 목록이 끼는 가드를 놓친다)"; fail=1; }

  grep -v "^assertNoServiceProjectDeps:" "$GLOG" > "$GLOG.miss"
  verify_guards "$GLOG.miss" >/dev/null 2>&1
  [ $? -eq 1 ] && echo "  OK   guards — 한 줄 누락 검출" || { echo "  FAIL guards 누락검출"; fail=1; }

  verify_guards "$TMP/nope.log" >/dev/null 2>&1
  [ $? -eq 1 ] && echo "  OK   guards — 로그 파일 부재 검출" || { echo "  FAIL guards 파일부재"; fail=1; }

  # ── merge-artifacts: 충돌 검출 ────────────────────────────────────────────
  MDIR="$TMP/merge"
  mkdir -p "$MDIR/src/shard-results-a/alpha/build/test-results/test" \
           "$MDIR/src/shard-results-b/beta/build/test-results/test"
  : > "$MDIR/src/shard-results-a/alpha/build/test-results/test/x.xml"
  : > "$MDIR/src/shard-results-b/beta/build/test-results/test/y.xml"
  ( cd "$MDIR" && bash "$ROOT/scripts/ci-test-matrix-lint.sh" --merge-artifacts src >/dev/null 2>&1 )
  if [ $? -eq 0 ] && [ -f "$MDIR/alpha/build/test-results/test/x.xml" ] \
                  && [ -f "$MDIR/beta/build/test-results/test/y.xml" ]; then
    echo "  OK   merge — 충돌 없으면 <module>/build/... 배치로 병합"
  else
    echo "  FAIL merge 정상 병합"; fail=1
  fi

  # 같은 상대 경로를 두 artifact 가 올린 경우 = 모듈 중복 배치 또는 변조
  mkdir -p "$MDIR/src/shard-results-b/alpha/build/test-results/test"
  : > "$MDIR/src/shard-results-b/alpha/build/test-results/test/x.xml"
  ( cd "$MDIR" && bash "$ROOT/scripts/ci-test-matrix-lint.sh" --merge-artifacts src >/dev/null 2>&1 )
  [ $? -eq 1 ] && echo "  OK   merge — 상대 경로 충돌 검출" || { echo "  FAIL merge 충돌검출"; fail=1; }

  ( cd "$MDIR" && bash "$ROOT/scripts/ci-test-matrix-lint.sh" --merge-artifacts nope >/dev/null 2>&1 )
  [ $? -eq 1 ] && echo "  OK   merge — artifact 디렉터리 부재 검출" || { echo "  FAIL merge 부재검출"; fail=1; }

  # ── 가드 목록 정본 ────────────────────────────────────────────────────────
  GB="$TMP/build.gradle"
  cat > "$GB" <<'BG'
tasks.named('check') {
	dependsOn 'assertOne', 'assertTwo'
}
BG
  got="$(BUILD_GRADLE="$GB" guard_names 2>/dev/null)"
  [ "$got" = "assertOne assertTwo" ] && echo "  OK   guard-list — build.gradle 에서 정본을 읽는다" \
    || { echo "  FAIL guard-list 읽기 ($got)"; fail=1; }

  # check 에 가드가 추가되면 그 가드의 성공 표식까지 요구해야 한다(드리프트 검출)
  printf 'assertOne: OK (x)\n' > "$TMP/g2.log"
  BUILD_GRADLE="$GB" verify_guards "$TMP/g2.log" >/dev/null 2>&1
  [ $? -eq 1 ] && echo "  OK   guard-list — 신규 가드 표식 누락 검출" || { echo "  FAIL guard-list 드리프트"; fail=1; }

  cat > "$TMP/bad.gradle" <<'BG'
// check 배선 없음
BG
  BUILD_GRADLE="$TMP/bad.gradle" guard_names >/dev/null 2>&1
  [ $? -eq 2 ] && echo "  OK   guard-list — check 배선 부재 검출" || { echo "  FAIL guard-list 배선부재"; fail=1; }

  [ "$fail" -eq 0 ] && echo "ci-test-matrix-lint --self-test: OK" || echo "::error::ci-test-matrix-lint --self-test 실패"
  return "$fail"
}

case "${1:-}" in
  "")               coverage ;;
  --merge-artifacts) merge_artifacts "${2:-}" ;;
  --verify-layout)  verify_layout ;;
  --verify-guards)  verify_guards "${2:-}" ;;
  --self-test)      self_test ;;
  *) echo "사용: $0 [--merge-artifacts <dir>|--verify-layout|--verify-guards <log>|--self-test]" >&2; exit 2 ;;
esac

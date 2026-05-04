# task-d011-harness-hardening — `/plan`·`/work` 공용 shell helper 4건 정비

> 작성: 2026-05-01
> 관련 Phase: Phase 3 잔여 부채 (Phase 4 MSA 분리 진입 전 처리)
> 관련 부채: D-011 (Tooling / Harness) — `docs/TASKS.md` Tech Debt 표 행 D-011
> 발견 컨텍스트: task-hpa-manifest Codex 리뷰 (2026-04-21). 원본 리뷰 산출물 `.cache/codex-reviews/diff-task-hpa-manifest-*-c{1,3}.json`
> 관련 ADR: 신규 작성 없음 — 본 task 는 내부 도구/하네스 정비로, 아키텍처 경계·외부 의존성·환경 변경을 동반하지 않음. (ADR 게이트 자동 통과 대상)

## 1. 목표

`/plan`·`/work` 공용 shell helper(`shared-logic.sh`, `scripts/timeout_wrapper.py`) 의 선-존재 견고성 결함 4건을 정리하여, 후속 모든 task 세션의 안전성 기반을 확보한다. 본 task 는 **harness 자체** 를 변경하므로, 변경분의 회귀 방지 자동화(Bats) 도 동일 task 범위에 포함한다.

세부 목표:

- **(a) 경로 인젝션 차단** — `task_id` 가 lock/state/diff 경로에 직접 보간되는 모든 helper(`hpx_lock_dir`, `hpx_state_path`, 그리고 동일 패턴을 쓰는 보조 helper) 에서 `[A-Za-z0-9._-]+` allowlist 검증을 강제. 비어있거나 패턴 외 문자가 포함되면 실패하도록 단일 진입 함수 `hpx_task_id_validate` 추가.
- **(d) timeout 0/음수/NaN 거부** — `scripts/timeout_wrapper.py` 가 `seconds <= 0`, `NaN`, `Inf` 를 거부하도록 검증. 잘못된 호출이 "정상 timeout(124)" 로 위장되는 것을 차단.
- **(b) `git add -N` 부작용 제거** — `hpx_diff_capture` 가 working tree 의 실제 git index 를 건드리지 않도록 격리된 임시 index (`GIT_INDEX_FILE`) 로 untracked 파일 intent-to-add 를 제한. 사용자의 staged 상태/`git add -p` 진행 중인 작업과 무관하게 동작.
- **(c) Bats 회귀 테스트 도입** — 위 (a)/(b)/(d) 변경분 + 핵심 helper 5종(`hpx_task_id_validate`, `hpx_lock_dir`, `hpx_state_path`, `hpx_lock_acquire`, `hpx_diff_capture`) 의 핵심 계약을 Bats 로 잠근다. 875+ 줄 helper 의 회귀 방지 최소 베이스라인을 마련.

## 2. 배경 / 제약

### 발견 경위 및 리스크 평가

- D-011 은 task-hpa-manifest 의 diff 리뷰 (`.cache/codex-reviews/diff-task-hpa-manifest-*-c{1,3}.json`) 에서 발견된 4건의 선-존재 결함 묶음. 본 task 범위 외였으므로 별도 task 로 분리하여 본 계획에서 해소.
- 우선순위 근거: (a) > (d) > (b) > (c)
  - (a) **경로 인젝션**: `task_id="../../etc/passwd"` 같은 입력이 들어오면 `docs/plans/../../etc/passwd.lock` 경로의 `mkdir`/`rm -rf` 가 실행될 수 있음. `hpx_lock_force_release` 가 `rm -rf "$lock_dir"` 를 수행(L152) 하므로 **임의 경로 삭제 위험** 가능. 외부 입력으로부터 `task_id` 를 받는 경로가 현재 슬래시 커맨드 인자 1개뿐이지만 차단은 기본 위생.
  - (d) **timeout 위장**: `timeout 0 codex exec ...` 이 즉시 124 로 종료되면, Codex 호출 결과 분석 단계에서 "정상 타임아웃" 으로 분기되어 실제 호출 자체가 일어나지 않은 상황을 가린다. degraded gate 의 `error_reason` 분류가 흐려짐.
  - (b) **index 부작용**: `git add -N` 은 user 의 진행 중 staging 작업과 충돌. `git status` / `git diff --cached` 결과가 일시적으로 오염. 사용자 워크플로 신뢰성에 직접 영향이지만 데이터 파괴는 없음.
  - (c) **회귀 방지 부재**: (a)/(b)/(d) 수정분이 미래에 다시 깨지는 것을 막을 자동화가 없음. 단독으로는 동작 변경이 없는 인프라 작업.

### 메타 주의 (harness 자기수정)

- 본 task 는 **`/plan` 자체가 사용 중인 harness 를 수정**한다. 변경 후에도 진행 중인 본 세션 (`task-d011-harness-hardening`) 의 lock/state 가 유효해야 한다.
  - allowlist `[A-Za-z0-9._-]+` 는 `task-d011-harness-hardening` 와 정합 ✅
  - 기존 `done/` 산하의 모든 task_id 도 정합 (검증: P8 의 회귀 가드)
- (b) git add -N 변경 시점에 진행 중 untracked 파일은 본 task 의 신규 파일 자체. 변경 전·후 모두 `hpx_diff_capture` 가 본 task 의 untracked 를 정확히 캡처해야 함. 변경 후 첫 `/work` 호출의 GP-3 게이트로 자체 검증.

### 비대상

- shared-logic.sh 전반의 리팩토링/구조 개선
- Bats 외 다른 테스트 프레임워크 도입
- 외부 의존성 신규 (Bats 자체는 dev-only, brew/apt 로 설치하는 표준 OSS — 의존성 등록 없음, 설치 가이드만 README 에 명시)
- `/ship` 또는 다른 helper 의 비-D-011 결함 정정 (별도 task 로 분리)
- Codex 호출 경로 자체의 타임아웃/재시도 정책 변경 (현재 `hpx_codex_timeout_seconds` 정책 유지)

## 3. 작업 항목

### Part A — 경로 인젝션 차단 (a, 우선순위 1)

- [ ] **P1.** `hpx_task_id_validate` 헬퍼 신규 (`shared-logic.sh`, 파일 상단 "공통 유틸" 섹션)
  - 시그니처: `hpx_task_id_validate <task_id>` — 통과 시 exit 0, 실패 시 stderr 메시지 + exit 1
  - 규약: `[A-Za-z0-9._-]+` allowlist, 길이 1~128, `..` 부분 문자열 금지, 선두 `-`/`.` 금지 (옵션 인자로 오인 방지)
  - 구현 방식: `case` 문 + `[[ ]]` 정규식 (Python 호출 회피, helper 자체가 가벼움)
- [ ] **P2.** `task_id` 를 경로에 보간하는 모든 helper 진입부에 `hpx_task_id_validate` 호출
  - **lock/state 경로 (1지점 커버)**: `hpx_lock_dir` / `hpx_state_path` 진입부에서 1회 검증 → 호출자(`hpx_lock_acquire`, `hpx_lock_force_release`, `hpx_lock_release`, `hpx_state_*` 전부) 자동 보호
  - **path 직접 보간 helper (개별 호출 필요)**:
    - `hpx_plan_lint` (L557-564) → `docs/plans/${task_id}.md`
    - `hpx_audit_append` (L738-743) → `docs/plans/.audit/${task_id}.md`
    - `hpx_diff_capture` (L832-854) → `.cache/diffs/diff-${task_id}-${ts}.patch`
    - `hpx_ship_pr_body_data` (L1113-1128) → `docs/plans/.audit/${task_id}.md` 및 plan path — 두 path 모두 검증 대상. `grep -n 'docs/plans/.audit/${task_id}\|docs/plans/${task_id}'` 로 전수 식별 후 모두 검증 추가
- [ ] **P3.** `.claude/commands/plan.md` / `work.md` 본문의 `RAW`/`ERR` 및 계획서 경로 보간 보호
  - `RAW=".cache/codex-reviews/{plan|diff}-${TASK_ID}-${TS}.json"`, `ERR=...stderr`, `docs/plans/${TASK_ID}.md` 모두 `TASK_ID` 보간
  - **slash command 진입 직후 1회 검증 강제**: 두 command 의 Step 1 ("인자 파싱 + task 확정") 직후에 `hpx_task_id_validate "$TASK_ID" || exit 1` 라인을 명시. 이후 본문은 검증된 `TASK_ID` 만 사용한다는 전제로 동작
  - 변경 파일: `.claude/commands/plan.md` Step 1 본문, `.claude/commands/work.md` Step 1 본문

### Part B — timeout wrapper 견고화 (d, 우선순위 2)

- [ ] **P4.** `scripts/timeout_wrapper.py` 의 seconds 파싱 검증 강화
  - 현재 `seconds = float(argv[1])` → `ValueError` 만 catch
  - 추가: `math.isnan(seconds) or math.isinf(seconds) or seconds <= 0` 인 경우 stderr 로 `invalid seconds: <value>` + `return 2`
  - GNU `timeout(1)` 호환을 위해 종료 코드 2 (잘못된 인자) 유지. 124 (정상 타임아웃) 로 오해되지 않음
- [ ] **P5.** 회귀 테스트 — Bats 단위 테스트로 0/-1/NaN/Inf/정상값 5케이스 검증 (Part D 에서 함께 작성)

### Part C — `git add -N` 부작용 제거 (b, 우선순위 3)

- [ ] **P6.** `hpx_diff_capture` 의 untracked 처리 격리
  - 변경 전: `git ls-files --others --exclude-standard -z | xargs -0 git add -N --` (실제 index 변경)
  - 변경 후: 격리된 임시 index 사용 — **현재 index 복사 + NUL 안전 파이프**
    ```bash
    local tmp_dir tmp_index git_dir real_index
    tmp_dir="$(mktemp -d -t hpx-diff-index.XXXXXX)"
    tmp_index="${tmp_dir}/index"
    trap 'rm -rf "$tmp_dir"' RETURN
    # 실제 .git/index 를 그대로 복사 → staged 변경(신규/수정 모두) 보존
    git_dir="$(git rev-parse --git-dir)"
    real_index="${git_dir}/index"
    if [ -f "$real_index" ]; then
      cp "$real_index" "$tmp_index"
    fi
    # (real_index 없는 unborn repo 는 빈 tmp_index 로 시작 — git 이 빈 파일을 수용)
    # NUL-delimited 파이프 — 변수 치환 회피로 공백/개행 파일명 안전
    GIT_INDEX_FILE="$tmp_index" git ls-files --others --exclude-standard -z 2>/dev/null \
      | GIT_INDEX_FILE="$tmp_index" xargs -0 -r git add -N -- 2>/dev/null || true
    GIT_INDEX_FILE="$tmp_index" git diff "${base}" >"${path}"
    ```
  - 트레이드오프: 임시 index 디렉토리 비용 (현재 `.git/index` 크기, 수십 KB ~ 수 MB). 본 프로젝트는 single-module Java repo 라 무시 가능. 대안 (`git diff --no-index /dev/null <file>` per-file) 은 정렬·헤더 일관성이 깨져 기각. **read-tree HEAD 방식은 staged 신규 파일이 diff 에서 누락되어 기각** (loop 2 검증)
  - **edge case 처리**:
    - **Unborn repo / .git/index 없음**: `[ -f "$real_index" ]` 가드 → 빈 `tmp_index` 로 시작 (git 이 0 byte index 를 빈 트리로 수용)
    - **untracked 0개**: `xargs -r` (`--no-run-if-empty`) 로 빈 입력 시 git add 미호출. macOS BSD xargs 호환 위해 GNU `findutils` 가정. **호환성 메모**: macOS 기본 xargs 는 `-r` 미지원 → 대체로 `if [ -n "$untracked" ]; then ... | xargs -0 ...; fi` 패턴 (현 helper 가 이미 사용) 유지하면서 NUL 파이프만 변경
    - **공백/개행 파일명**: NUL-delimited 파이프 직결 (변수 경유 X) 로 안전
    - **`git rev-parse --git-dir` 실패** (git repo 외부): `set -euo pipefail` 하에서 일찍 실패. 호출자 (Step 7) 는 항상 repo 루트라 OK
  - **trap 보장**: `trap 'rm -rf "$tmp_dir"' RETURN` (bash) 로 중간 실패에도 정리. RETURN trap 은 함수 종료 시점에 동작 (bash 4+ 필요, macOS 기본 bash 3.2 환경 대비 `EXIT` 도 함께 등록 권장 — 단, 호출자 set -e 와의 상호작용 주의: 호출자 trap 을 덮어쓰지 않도록 함수 진입부에 `local prev_trap=$(trap -p RETURN)` 후 복원)
  - **호환성 확인 결과**: macOS 환경 우선 (사용자 zsh/macOS) — bash 3.2 의 `RETURN` trap 은 `function` 키워드 정의에서만 동작. 본 helper 는 `hpx_diff_capture()` POSIX 정의라 RETURN trap 미지원 가능. **fallback 전략**: trap 대신 함수 종료 직전 `rm -rf "$tmp_dir"` 명시 호출 + 중간 실패 대비 `(... ) || { rm -rf "$tmp_dir"; return 1; }` subshell 격리

### Part D — Bats 회귀 테스트 (c, 우선순위 4)

- [ ] **P7.** Bats 디렉터리 + README 추가
  - 위치: `.claude/scripts/tests/bats/`
  - 파일: `README.md` (실행 방법: `bats .claude/scripts/tests/bats/`, 설치: `brew install bats-core`)
  - `.gitignore` 미변경 (테스트 산출물 없음)
- [ ] **P8.** `task_id_validate.bats` — P1 검증
  - **대표 정상 케이스 (정적)**: `task-foo`, `task-d011-harness-hardening` (현재 작업 중인 task_id 가 정책에 부합함을 분리 증명)
  - **회귀 가드 (동적, 별도 테스트 케이스로 분리)**: `for f in docs/plans/done/*.md; do hpx_task_id_validate "$(basename ${f%.md})"; done` — 실제 done/ basename 6개 (`task-d010-outbox-trace-context`, `task-hpa-manifest`, `task-hpa-manifest.audit`, `task-jmeter-to-k6`, `task-jmeter-to-k6.audit`, `task-loadtest-session-c`) 가 모두 통과함을 보장
  - **거부 케이스**: 빈 문자열, `../foo`, `foo/..`, `foo..bar` (부분 문자열), `-foo`, `.foo`, 공백 포함 (`foo bar`), 128자 초과, 특수문자 (`foo/bar`, `foo;rm`, `foo$x`, `foo\nbar`, `foo\tbar`)
- [ ] **P9.** `lock_state_paths.bats` — P2 통합 + 부정 테스트
  - **정상 동작**:
    - `hpx_lock_acquire` re-enter (같은 session_id) idempotent 동작
    - `hpx_lock_force_release` 가 정상 task 만 해제
  - **부정 테스트 (악성 id 가 부작용 0건임을 보장)**:
    - `hpx_lock_dir "../etc"` → exit 1 + stderr 메시지, lock 디렉토리 생성 0건 (`ls /tmp/etc.lock` 없음)
    - `hpx_state_path "../foo"` → exit 1, state 파일 생성 0건
    - `hpx_lock_acquire "foo;rm"` → exit 1, mkdir 0건
    - `hpx_lock_force_release "../etc"` → exit 1, **`rm -rf` 가 외부 경로에 도달하지 않음** (검증: 사전에 만든 sentinel 파일이 여전히 존재)
    - `hpx_state_write "../foo" "{}"` → exit 1, 외부 경로 파일 생성 0건
    - `hpx_state_read "../foo"` → exit 1, 파일 read 0건
    - `hpx_state_exists "../foo"` → exit 1 (false positive 차단)
- [ ] **P10.** `plan_audit_paths.bats` — `hpx_plan_lint`, `hpx_audit_append` 보호 검증
  - `hpx_plan_lint "../etc/passwd"` → exit 1 + 외부 경로 read 0건
  - `hpx_audit_append "../etc/foo" "test"` → exit 1 + 외부 경로 파일 생성 0건
- [ ] **P11.** `diff_capture.bats` — P6 부작용 격리 + 기능 정확성 검증
  - **격리 시나리오** (BATS_TEST_TMPDIR 안에 temp git repo 생성):
    - 파일 1개 modified (`git add a.txt` 수정) + 파일 1개 staged 신규 (`git add c.txt`) + 파일 1개 untracked (`b.txt`) + 공백/개행 포함 untracked (`b c.txt`, `b\nc.txt`) → `hpx_diff_capture` 호출
    - 호출 후 `git status --porcelain` 출력이 호출 전과 **완전 동일** 함 (diff -u 검증)
    - 호출 후 `.git/index` 의 hash 가 변하지 않음 (`sha256sum .git/index` 호출 전후 비교)
  - **기능 회귀 (loop 2 추가)**:
    - **untracked 파일** 이 diff 산출물에 포함됨 (`grep "^+++ b/b.txt"` 매치)
    - **staged 신규 파일** (`c.txt`) 이 diff 산출물에 포함됨 (`grep "^+++ b/c.txt"` 매치 — read-tree HEAD 방식 회귀 차단)
    - **staged 수정 파일** (`a.txt`) 이 diff 산출물에 포함됨
  - **edge case**: unborn repo (`git init` 직후 commit 없음, `.git/index` 없음) 에서도 동작 (빈 tmp_index 로 시작)
  - **task_id 보호**: `hpx_diff_capture "../etc" "$ts"` → exit 1
- [ ] **P12.** `timeout_wrapper.bats` — P4 검증 (확장)
  - **거부 케이스 (exit 2 + stderr "invalid seconds")**: `0`, `-1`, `-0.5`, `NaN`, `nan`, `Inf`, `+Inf`, `Infinity`, `1e309` (Python `float()` 가 inf 로 받아들임), `abc`, 빈 인자
  - **정상 케이스**: `0.001 sleep 0` → exit 0, `1 sleep 0.1` → exit 0, `1 sleep 5` → exit 124 (grace period 후)
  - 각 거부 케이스에서 stderr 에 `invalid seconds:` substring 포함 여부 확인
  - 마지막 timeout 케이스 grace period 종료까지 최대 ~3.5s 소요. CI 시간 영향 없음 (로컬 dev-only)

### Part E — 문서 동기화

- [ ] **P13.** `docs/TASKS.md` D-011 행 우선순위 컬럼 `중간` → `~~중간~~ **해결됨**` 갱신, "완료된 작업" 표 새 행 추가
- [ ] **P14.** `docs/progress/PHASE3.md` 에 본 task 엔트리 추가 (Phase 3 잔여 부채 종결)
- [ ] **P15.** `docs/02-architecture.md` 확인만 수행 (파일 변경 없음) — loop 3 검증으로 §12 가 `.claude/` 트리를 다루지 않음을 확인 (`grep -c '.claude' docs/02-architecture.md` = 0). 본 P15 는 "확인 후 변경 없음" 으로 마킹하여 종결. 영향 파일 표에서 `docs/02-architecture.md` 제거됨

## 4. 영향 파일

| 파일 | 변경 유형 | Part |
|------|-----------|------|
| `.claude/scripts/shared-logic.sh` | 함수 추가 (`hpx_task_id_validate`) + 기존 함수 진입부 검증 + `hpx_diff_capture` 격리 index 전환 | A, C |
| `scripts/timeout_wrapper.py` | seconds 검증 강화 (`math.isnan/isinf`, `<= 0`) | B |
| `.claude/commands/plan.md` | Step 1 직후 `hpx_task_id_validate "$TASK_ID"` 호출 추가 | A (P3) |
| `.claude/commands/work.md` | Step 1 직후 `hpx_task_id_validate "$TASK_ID"` 호출 추가 | A (P3) |
| `.claude/scripts/tests/bats/README.md` | 신규 (실행/설치 가이드) | D |
| `.claude/scripts/tests/bats/task_id_validate.bats` | 신규 | D |
| `.claude/scripts/tests/bats/lock_state_paths.bats` | 신규 | D |
| `.claude/scripts/tests/bats/plan_audit_paths.bats` | 신규 | D |
| `.claude/scripts/tests/bats/diff_capture.bats` | 신규 | D |
| `.claude/scripts/tests/bats/timeout_wrapper.bats` | 신규 | D |
| `docs/TASKS.md` | D-011 행 + 완료 표 | E |
| `docs/progress/PHASE3.md` | 본 task 엔트리 | E |

## 5. 검증 방법

### 자동 (Bats)

```bash
brew install bats-core  # 또는 apt-get install bats
bats .claude/scripts/tests/bats/
# 기대: 모든 테스트 통과 (예상 25~30 케이스)
```

### 수동 (해당 sanitize 차단 회귀)

- 본 task 의 `/plan` 진행 중 lock 이 정상 동작 확인 (이미 검증 — TASK_ID `task-d011-harness-hardening` 가 allowlist 통과)
- `done/` 하위 모든 task_id 가 신규 sanitize 를 통과하는지 위 Bats `task_id_validate.bats` 의 회귀 케이스로 보장
- 본 task `/work` 의 GP-3 (diff 캡처) 가 P6 변경 후 사용자의 staged 변경을 오염시키지 않는지 자체 검증 (변경 전후 `git status` 비교)

### 회귀 (Java 빌드)

- `./gradlew test` — 본 task 는 Java 코드 변경 0건이라 영향 없어야 함. 통과 확인만 수행.

## 6. 완료 조건

- [ ] P1 ~ P15 전부 체크 완료 (P15 는 조건부 — `.claude/` 트리 미언급 시 미수행 마킹)
- [ ] `bats .claude/scripts/tests/bats/` 전 케이스 통과
- [ ] `./gradlew test` 회귀 0건
- [ ] D-011 가 `docs/TASKS.md` 에서 `해결됨` 으로 마킹
- [ ] PR 생성 + 머지

## 7. 트레이드오프 및 결정 근거

| 결정 | 채택 | 기각 대안 | 근거 |
|------|------|-----------|------|
| (a) sanitize 위치 | `hpx_lock_dir` / `hpx_state_path` 진입부 1지점 + `hpx_diff_capture` 진입부 1지점 | 호출자 helper 마다 검증 호출 | 호출 누락 위험 최소화. 단, `hpx_diff_capture` 는 path 직접 빌드라 별도 호출 |
| (a) allowlist 정의 | `[A-Za-z0-9._-]+`, 1~128자, `..` 금지, 선두 `-`/`.` 금지 | URL-safe base64, UUID-only | 기존 task_id 컨벤션 (`task-d010-outbox-trace-context`) 호환 + `..` traversal 차단 |
| (b) 격리 방식 | 임시 `GIT_INDEX_FILE` (현재 `.git/index` 복사) | (1) per-file `git diff --no-index` — diff 출력 일관성 깨짐 (2) `git read-tree HEAD` 후 `--others` 만 add — staged 신규 파일이 diff 에서 누락 (loop 2 검증) | diff 출력 일관성 + staged 변경 보존 둘 다 충족 |
| (c) 테스트 프레임워크 | Bats (bats-core) | shellcheck 만 / Python pytest 로 shell 호출 | 표준 Shell 단위 테스트 표준. macOS/Linux 양쪽 brew/apt 단일 명령 설치 |
| (d) 종료 코드 | `2` (잘못된 인자) | `124` 유지하되 stderr 만 강화 | GNU `timeout` 호환. 124 는 "정상 타임아웃" 의미를 침범하지 말아야 함 |

## 8. 후속 (Out-of-Scope)

- shared-logic.sh 875+ 줄 전체 리팩토링 (모듈화, 함수당 길이 단축) — 본 task 는 결함 수정 + 회귀 방지 베이스라인 한정
- `/ship` 의 git push 경로 견고성 (별도 발견 시 별도 task)
- shared-logic.sh 외 `.claude/scripts/` 산하 다른 스크립트 (없는 것으로 확인됨, 변경 시 별도 task)

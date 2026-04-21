# /ship — 커밋 / 푸시 / PR 생성 / done 갱신

사용법:
- `/ship` — active state (stage ∈ { `work.done`, `ship.*` }) 에 대해 **dry-run 모드** 실행 (기본)
- `/ship <task-id>` — 지정 task 의 state 로 dry-run
- `/ship --execute` — 실제 commit / push / `gh pr create` / `/done` 수행
- `/ship <task-id> --execute` — 지정 task 에 대해 execute

입력: `$ARGUMENTS`

본 커맨드는 `harness-plan.md` §6-3-3 (11-step) 을 구현한다.
`/ship` 자체는 Codex 를 호출하지 않는다 (shell precheck / commit / push / gh 만).

---

## 모드 구분

- **dry-run (default)**: Steps 1–2 실행, Steps 3/5 산출물 미리보기만, Steps 4/7/8/9/10 건너뜀. state 는 **갱신하지 않음**. `.cache/` 산출물만 생성.
- **execute**: 모든 Step 풀 실행. state 갱신. 부작용 발생.

dry-run 이 passed 된 뒤 **같은 세션에서 `--execute` 로 재호출**하면 Steps 3 부터 deterministic 하게 재계산 후 실행.

---

## 실행 규칙

- 모든 Bash 호출은 `bash -c '...'` 서브셸
- 각 Bash 호출 선두에 `set -euo pipefail && source .claude/scripts/shared-logic.sh`
- state.json 은 `hpx_state_write` 로 원자 치환 (execute 모드에서만)
- 외부 부작용 직전 → state 예약 → 부작용 → state 재기록
- **`git add -A` 절대 금지**. 파일 명시 커밋만
- dry-run 은 lock 을 획득하되 종료 시 해제 (다른 세션 간섭 차단)

---

## 11-step 절차

### Step 1. 인자 파싱 + task 확정 + state 로드 + lock

```bash
bash -c 'set -euo pipefail
source .claude/scripts/shared-logic.sh
# 아래 변수는 Claude 가 $ARGUMENTS 파싱으로 주입
echo "TASK_ID=$TASK_ID"
echo "MODE=$MODE"  # dry-run | execute
'
```

- `$ARGUMENTS` 파싱: `--execute` 토큰 제거 후 남은 인자가 `TASK_ID`. 없으면 active state 스캔
- active state 스캔: `docs/plans/*.state.json` 중 `stage ∈ { work.done, ship.* }` 을 updated_at 내림차순 → 후보 제시
- `hpx_state_exists "$TASK_ID"` false → "state 가 없습니다. /work 를 먼저 완료하세요." 종료
- state 로드 후 `stage` 확인:
  - `work.done` 또는 `ship.*` 이면 진행
  - 그 외 → "/ship 은 work.done 이후에만 진행 가능" 안내 후 종료
- `SID=$(hpx_lock_acquire "$TASK_ID" ship "$(python3 -c 'import json; print(json.load(open("docs/plans/'"$TASK_ID"'.state.json")).get("session_id",""))')")` — state.session_id 재사용으로 re-enter idempotent
- HEAD 와 `state.branch` 교차 검증 (`git branch --show-current` == `state.branch`). 불일치 → 사용자 보고 후 중단
- `work.done` 진입에서는 `last_diff_path` 기준 **state drift detector** 를 먼저 수행:
  ```bash
  bash -c 'set -euo pipefail
  source .claude/scripts/shared-logic.sh
  STAGE=$(python3 -c "import json; print(json.load(open('"'"'docs/plans/'"$TASK_ID"'.state.json'"'"')).get('"'"'stage'"'"','"'"''"'"'))")
  DIFF_PATH=$(python3 -c "import json; print(json.load(open('"'"'docs/plans/'"$TASK_ID"'.state.json'"'"')).get('"'"'last_diff_path'"'"','"'"''"'"'))")
  if [ "$STAGE" = "work.done" ] && [ -n "$DIFF_PATH" ] && [ -f "$DIFF_PATH" ]; then
    STATUS=$(hpx_diff_absorption_status "$DIFF_PATH")
    echo "DRIFT_STATUS=$STATUS"
    if [ "$STATUS" = "all_absorbed" ]; then
      hpx_diff_files "$DIFF_PATH" || true
    fi
  fi
  '
  ```
- `DRIFT_STATUS=all_absorbed` 이면 **GS-0 게이트**:
  ```
  === state drift 감지 ===
  last_diff_path 의 변경이 현재 working tree 에 없습니다.
  diff 캐시 기준 변경은 이미 커밋에 흡수되었을 가능성이 높습니다.

    [1] archive — state 정리 후 종료
    [2] 종료
  >
  ```
  - `[1]` 선택 시 audit log 에 `state_drift_archive` 기록 후 state 를 `docs/plans/.archive/` 로 이동하고 종료
  - `[2]` 선택 시 audit log 에 `state_drift_abort` 기록 후 종료
- `DRIFT_STATUS=partially_live` 이면 partial drift 로 간주하고 경고 후 종료. 자동 진행 금지

### Step 2. Consistency precheck (GS-1 conditional)

```bash
bash -c 'set -euo pipefail
source .claude/scripts/shared-logic.sh
RES=$(hpx_consistency_precheck "'"$TASK_ID"'")
STATUS=$(echo "$RES" | sed -n 1p)
LOG=$(echo "$RES" | sed -n 2p)
EC=$(echo "$RES" | sed -n 3p)
echo "STATUS=$STATUS LOG=$LOG EC=$EC"
tail -30 "$LOG" 2>/dev/null || true
'
```

분기 (§6-3-3 Step 2 + §7-5-E):
- `STATUS=ok` (warnings 0) → 자동 통과 (§6-2 conditional, GS-1 skip). `hpx_gate_events_append` 로 `shown=false, auto_passed=true` 기록
- `STATUS=warnings` → **GS-1 게이트** 노출 (log 요약 + `[MISS] ADR-NNNN` 항목 제시):
  ```
  === Consistency precheck — warnings ===
  [MISS] ADR-0009 — 해당 ADR 파일 없음
  ...
    [1] 수정 (편집 후 재실행)
    [2] 무시하고 진행 (사유 필수)
    [3] 종료
  >
  ```
  - 선택 `[2]` 시 사유를 audit log + state 에 기록, PR 본문에 "Skipped consistency checks" 섹션 추가 플래그 설정
- `STATUS=script_error` → §7-5-E 실행 실패 분기: stderr 요약 + exit code 제시, 동일 3 선택지 (환경 수정/무시+사유/종료)
- `STATUS=unavailable` → "docs/consistency-hints.sh 미존재. Skip." 안내. audit log 한 줄만 기록. 자동 진행

**dry-run 이면**: precheck 만 실행하고 게이트 미노출. 결과 요약만 출력 후 Step 3 로 진행.

**execute 통과 후**:
- `stage=ship.precheck` 기록 (state 원자 저장)

### Step 3. 커밋 분할 제안 (GS-2 always)

```bash
bash -c 'set -euo pipefail
source .claude/scripts/shared-logic.sh
DIFF_PATH=$(python3 -c "import json; s=json.load(open('"'"'docs/plans/'"$TASK_ID"'.state.json'"'"')); print(s.get('"'"'last_diff_path'"'"','"'"''"'"'))")
if [ -z "$DIFF_PATH" ] || [ ! -f "$DIFF_PATH" ]; then
  # 재진입이거나 diff 캐시 유실 — fresh capture
  TS=$(hpx_epoch_ts)
  DIFF_PATH=$(hpx_diff_capture "'"$TASK_ID"'" "$TS")
fi
echo "DIFF_PATH=$DIFF_PATH"
hpx_commit_plan_group "$DIFF_PATH"
'
```

위 TSV 출력을 받아 Claude 가 §10-3 기준으로 partition 결정:
- category ∈ `{adr, docs, test, chore, src}` 별로 묶되, src 는 필요 시 scope(패키지/도메인) 기준 분할
- 한 커밋 = 한 분류 (mixed 금지)
- 한 커밋 100파일 초과 → 재분할
- ADR/계획서는 별도 커밋

예상 커밋 메시지 생성 (§10-3 표 참조):
- `feat(<scope>): ...` / `fix(<scope>): ...` / `refactor(<scope>): ...` / `test(<scope>): ...` / `docs(adr): ADR-NNNN ...` / `chore(<scope>): ...`

**commit_plan 구조** (state 원자 저장용):
```json
[
  {
    "partition_id": "p1",
    "category": "src",
    "scope": "global/cache",
    "subject": "feat(cache): HarnessSmokeTtl — TTL 유틸 도입",
    "files": ["src/main/java/com/peekcart/global/cache/HarnessSmokeTtl.java"],
    "line_count": 24
  }
]
```

**GS-2 게이트 (always)** 미리보기:
```
=== 커밋 분할 제안 (N 개 partition) ===
p1. feat(cache): HarnessSmokeTtl — TTL 유틸 도입
    src/main/java/com/peekcart/global/cache/HarnessSmokeTtl.java  (+24)
p2. test(cache): HarnessSmokeTtl 3 case 추가
    src/test/java/com/peekcart/global/cache/HarnessSmokeTtlTest.java  (+23)

  [1] 승인 (이 분할로 진행)
  [2] 수정 (partition 재지정)
  [3] 종료
>
```

**dry-run 이면**: 미리보기 후 "execute 모드에서 확정" 안내. state 미갱신.

**execute 통과 후**:
- `commit_plan[]` 을 state 에 원자 저장 → `stage=ship.partition.previewed`
- audit log: GS-2 결정 1 엔트리

### Step 4. 커밋 생성

```bash
bash -c 'set -euo pipefail
source .claude/scripts/shared-logic.sh
# Claude 가 commit_plan 각 partition 에 대해 다음 루프를 순차 실행:
git add -- <files_of_partition>
if git diff --cached --quiet; then
  echo "partition=<id> staged diff empty"
  exit 20
fi
git commit -m "<subject>"
SHA=$(git rev-parse HEAD)
echo "partition=<id> sha=$SHA"
'
```

- `git add -A` **금지**. partition 의 `files[]` 만 명시
- `git add` 직후 `git diff --cached --quiet` 이면 fail-fast 로 중단. 이는 stale state/drift 재발생 신호로 간주하며 Step 1 detector 경로로 되돌아가야 함
- 각 커밋 직후 `git rev-parse HEAD` 로 sha 수집 → state 의 `created_commits[]` 에 `{partition_id, sha, subject, ts}` append → 원자 저장
- 재진입 시 재커밋 방지 교차 검증:
  ```bash
  # 마지막 N 개 커밋 subject 를 state.commit_plan 의 예상 subject 와 비교
  git log --pretty=format:'%H %s' -n <N> | ...
  ```
  이미 생성된 partition 은 skip
- 모든 partition 완료 후 → `stage=ship.commits.created`

**dry-run 이면**: Step 4 전체 skip. "execute 모드에서 커밋 예정" 안내.

### Step 5. PR 본문 생성

```bash
bash -c 'set -euo pipefail
source .claude/scripts/shared-logic.sh
DATA=$(hpx_ship_pr_body_data "'"$TASK_ID"'")
echo "$DATA"
'
```

위 JSON 데이터를 근거로 §10-2 템플릿을 채워 PR 본문 작성:

```markdown
## Why
> <task 배경: plan.md §1 또는 §2 의 목적. Task 링크 포함>

## What
> <한 줄 요약>
- completed_plan_items[] 를 bullet 으로 변환

## How
> <핵심 구현 결정. ADR 언급이 있으면 (see ADR-NNNN) 로 인용>

## Test plan
- [ ] 단위 테스트
- [ ] 통합 테스트
- [ ] 수동 확인

## 관련
- Task: docs/TASKS.md §...
- Plan: docs/plans/<task-id>.md
- Commits:
  - <commit_subjects[]>
```

**조건부 섹션**:
- `p0_ignores[]` 비어있지 않으면 (Q19 default):
  ```
  ## Skipped P0 findings
  > 본 PR 은 다음 P0 findings 를 수용하지 않았습니다. 사유:
  - <reason 1>
  - <reason 2>
  ```
- consistency precheck 에서 `무시하고 진행` 선택했으면:
  ```
  ## Skipped consistency checks
  > 사유: <user reason>
  ```

본문을 `.cache/pr-body-${TASK_ID}.md` 에 저장 (재시도 시 재사용 — §7-5-D).

### Step 6. GS-3 게이트 (always, 본문 미리보기)

```
=== PR 본문 미리보기 (.cache/pr-body-<task>.md, N 줄) ===
## Why
...

  [1] 승인 (이 본문으로 진행)
  [2] 수정 (본문 편집 후 재검토)
  [3] 종료
>
```

**dry-run 이면**: 미리보기만. `.cache/pr-body-*.md` 는 생성 OK. 게이트 미노출.

### Step 7. Push (`git push -u origin <branch>`)

**execute 모드에서만**. dry-run 은 전체 skip.

```bash
bash -c 'set -euo pipefail
source .claude/scripts/shared-logic.sh
BRANCH=$(git branch --show-current)
git push -u origin "$BRANCH"
'
```

- 성공 → state: `push_status=pushed`, `remote_branch=<branch>`, `ship_resume_cursor=pr.pending`, `stage=ship.pushed`
- `Everything up-to-date` → 동일하게 성공 처리 (멱등)
- 실패 → §7-5-C ladder:
  - `fetch first` / non-fast-forward → `git fetch origin` 후 사용자에게 rebase 여부 확인 (자동 rebase X)
  - auth failure → `gh auth login` / 인증 갱신 안내, 자동 재시도 X
  - network → 30 초 후 1 회 자동 재시도
  - state: `push_status=failed`, `ship_resume_cursor=push.failed` 기록 후 종료
  - 재호출 시 Step 7 부터 재진입

### Step 8. PR 생성 (`gh pr list` 선조회 후 `gh pr create`)

**execute 모드에서만**.

```bash
bash -c 'set -euo pipefail
source .claude/scripts/shared-logic.sh
BRANCH=$(git branch --show-current)
EXISTING=$(gh pr list --head "$BRANCH" --state open --json url -q ".[0].url" 2>/dev/null || true)
if [ -n "$EXISTING" ]; then
  echo "PR_URL=$EXISTING (existing)"
else
  PR_URL=$(gh pr create \
    --base "$(hpx_base_branch_name)" \
    --head "$BRANCH" \
    --title "<Claude 가 commit 1 subject 를 제목으로 변환>" \
    --body-file ".cache/pr-body-'"$TASK_ID"'.md")
  echo "PR_URL=$PR_URL"
fi
'
```

- PR title 은 commit_plan[0].subject 또는 Claude 가 요약 1줄
- 성공 → state: `pr_url`, `ship_resume_cursor=done.pending`, `stage=ship.pr.created`
- 실패 → §7-5-D ladder:
  - `gh: command not found` → 수동 PR 생성 안내 (본문 경로 + URL 템플릿 제시), 종료
  - `gh auth` 만료 → 갱신 안내 + 재시도 선택
  - 5xx / 네트워크 → 60 초 후 1 회 자동 재시도
  - API rate limit → `Retry-After` 대기 고지, 수동 재시도
  - 3회 실패 → "수동 PR 생성 (본문 파일 제시) / 종료" 선택. TASKS 미갱신
  - state: `ship_resume_cursor=pr.failed`. 본문 `.cache/pr-body-*.md` 재사용 보장

### Step 9. `/done` 상당 로직 (PR 성공 후에만)

**execute 모드 + `pr_url` 확정된 경우에만 실행**. dry-run 은 skip.

Claude 는 다음을 직접 수행 (`/done` 과 동일한 판단 과정):

1. `docs/TASKS.md` 를 읽어 현재 `🔄` Task 의 완료 항목을 `🔲` → `✅` 로 갱신
2. Task 전체가 완료되면 Task 상태도 `🔄 진행 중` → `✅ 완료`
3. 결정 사항 분류 (ADR 우선):
   - ADR 후보 (대안 비교 / 후속 전제) → 먼저 ADR 작성, progress 는 참조만
   - 구현 디테일 → progress 표에 직접 기록
   - 확신 없으면 사용자에게 질문
4. 활성 ADR 상태 점검: `Proposed → Accepted` 전환, 대체된 ADR Status 갱신
5. `docs/progress/PHASE{N}.md` 작업 이력 추가 (PR URL 포함)
6. Layer 1 문서 영향 시 해당 문서 (01~07) 갱신 (What 만, Why 는 ADR)

성공 후:
- state: `done_applied=true`, `stage=ship.done`
- audit log append: `## YYYY-MM-DD HH:MM — /done applied (PR <pr_url>)` + 갱신 항목 요약

실패 (편집 중 오류) → 사용자에게 보고 후 종료. TASKS 미갱신, PR 은 이미 존재 → 다음 호출 시 Step 9 만 재시도 (재진입 매트릭스 `ship.pr.created` 행).

### Step 10. state.json archive (Q24 default = archive)

```bash
bash -c 'set -euo pipefail
mkdir -p docs/plans/.archive
mv "docs/plans/'"$TASK_ID"'.state.json" "docs/plans/.archive/'"$TASK_ID"'.state.$(hpx_utc_ts).json"
'
```

- archive 디렉토리는 gitignore 대상 (`docs/plans/*.state.json` 패턴에 포함 안 됨 — 별도 `.archive/` 도 gitignore 에 추가 필요 → P3 smoke 검증 시 확인)
- lock 해제: `hpx_lock_force_release "$TASK_ID"`

### Step 11. PR URL 반환

```
=== /ship 완료 ===
Task: <task-id>
PR: <pr_url>
Commits: <N>개 생성
Branch: <branch>
```

dry-run 모드에서 종료 시:
```
=== /ship dry-run 완료 ===
다음 실행 예상:
- Commits: <N>개
- Push: origin/<branch>
- PR body: .cache/pr-body-<task-id>.md
- 실제 실행: /ship <task-id> --execute
```

---

## 재진입 매트릭스 (§6-3-3 v4)

| 현재 stage / cursor | 확인 | 다음 Step |
|-----------|----|----------|
| 없음 / `work.done` | — | Step 2 (precheck) 부터 |
| `work.done` + drift=`all_absorbed` | cached diff files vs current uncommitted 불일치 | **GS-0 (`archive` / `종료`)** |
| `ship.precheck` | — | Step 3 (분할 제안) |
| `ship.partition.previewed` | — | Step 4 (커밋 생성) |
| `ship.commits.created`, cursor 없음 | `created_commits[]` vs `git log` 교차 확인 | Step 5 (PR 본문) |
| `ship.commits.created` + cursor=`push.failed` | `push_status=="failed"` + 원격 반영 재확인 | **Step 7 (push 재시도)** |
| `ship.pushed` | `git ls-remote` + `gh pr list --head` | Step 8 (PR 조회 후 없을 때만 생성) |
| `ship.pushed` + cursor=`pr.failed` | `pr_url` 부재 + `gh pr list` 선조회 | **Step 8 (PR 생성 재시도)** |
| `ship.pr.created` | `pr_url` 필수 + `done_applied==false` | **Step 9 (`/done` 재시도)** |
| `ship.done` | `done_applied==true` | Step 10 (archive) 만, 완료 |

---

## 사용자 게이트 UX

### GS-1 (warnings)
```
=== Consistency precheck — warnings ===
[MISS] ADR-0009 — 해당 ADR 파일 없음

  [1] 수정 (편집 후 재실행)
  [2] 무시하고 진행 (사유 필수)
  [3] 종료
>
```

### GS-1 (script_error — §7-5-E)
```
=== Consistency precheck 실행 실패 (exit=127) ===
stderr:
  bash: docs/consistency-hints.sh: Permission denied

  [1] 환경 수정 후 재실행
  [2] 무시하고 진행 (사유 필수)
  [3] 종료
>
```

### GS-2 (partition preview)
```
=== 커밋 분할 제안 (N 개 partition) ===
p1. feat(cache): ... (+24)
p2. test(cache): ... (+23)

  [1] 승인
  [2] 수정
  [3] 종료
>
```

### GS-3 (PR body preview)
```
=== PR 본문 미리보기 ===
## Why
...

  [1] 승인
  [2] 수정
  [3] 종료
>
```

---

## 비용/빈도 제어

- `/ship` 은 Codex 호출 없음 → tokens 측정 대상 아님 (§7-6-1 `_metrics.tsv` 는 plan/work 만)
- Push / PR 자동 재시도 상한: network 1회 (§9-2)
- Step 9 `/done` 실패는 재시도가 아니라 중단 + 다음 호출 재진입

#!/usr/bin/env bash
# shared-logic.sh — Claude × Codex 하네스 공용 shell 함수
#
# Phase 0a 결과 B (nested slash 불가) 로 인해 /plan, /work, /ship 에서
# /sync, /next, /done 을 직접 호출할 수 없으므로 공용 로직을 본 파일에 둠.
#
# 사용법 (Claude 가 Bash 도구로 호출):
#   source .claude/scripts/shared-logic.sh
#   hpx_lock_acquire <task-id> <command>
#   ...
#
# 함수 접두사: hpx_  (harness prototype)

# ---------- 공통 유틸 ----------

hpx_utc_ts() {
  date -u +%Y%m%dT%H%M%SZ
}

hpx_epoch_ts() {
  date +%s
}

hpx_session_id() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    printf '%s-%s' "$(hpx_epoch_ts)" "$$"
  fi
}

# hpx_timeout_prefix [seconds]
# echo 으로 timeout 명령 prefix 를 출력. eval 로 사용.
hpx_timeout_prefix() {
  local secs="${1:-60}"
  if command -v timeout >/dev/null 2>&1; then
    printf 'timeout %s' "$secs"
  elif command -v gtimeout >/dev/null 2>&1; then
    printf 'gtimeout %s' "$secs"
  elif [ -f scripts/timeout_wrapper.py ]; then
    printf 'python3 scripts/timeout_wrapper.py %s' "$secs"
  else
    printf '__NO_TIMEOUT_PROVIDER__'
    return 1
  fi
}

# ---------- Lock (mkdir 원자성) ----------

# hpx_lock_dir <task_id>
hpx_lock_dir() {
  printf 'docs/plans/%s.lock' "$1"
}

# hpx_lock_acquire <task_id> <command> [existing_session_id]
# - existing_session_id 가 주어지고 lock meta 의 session_id 와 일치하면 re-enter (idempotent)
# - 성공 시 stdout 에 session_id 출력, exit 0
# - 실패 시 exit 1
hpx_lock_acquire() {
  local task_id="$1"
  local command="$2"
  local reuse_session="${3:-}"
  local lock_dir
  lock_dir="$(hpx_lock_dir "$task_id")"
  local pid_file="$lock_dir/pid"
  local meta_file="$lock_dir/meta.json"

  mkdir -p docs/plans >/dev/null 2>&1 || true

  if ! mkdir "$lock_dir" 2>/dev/null; then
    local existing_session=""
    if [ -f "$meta_file" ]; then
      existing_session="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("session_id",""))' "$meta_file" 2>/dev/null || true)"
    fi

    # Re-enter: caller provided a session id that matches existing lock
    if [ -n "$reuse_session" ] && [ -n "$existing_session" ] && [ "$reuse_session" = "$existing_session" ]; then
      printf '%s\n' "$existing_session"
      return 0
    fi

    local existing_pid=""
    if [ -f "$pid_file" ]; then
      existing_pid="$(cat "$pid_file" 2>/dev/null || true)"
    fi
    if [ -n "$existing_pid" ] && kill -0 "$existing_pid" 2>/dev/null; then
      printf 'lock: 다른 세션 진행 중 (pid=%s, session=%s, lock=%s). 자동 삭제 금지.\n' \
        "$existing_pid" "${existing_session:-unknown}" "$lock_dir" >&2
      return 1
    fi
    printf 'lock: stale 가능 lock 발견 (pid=%s, session=%s, lock=%s). 자동 삭제 금지 — meta 확인 후 수동 해제.\n' \
      "${existing_pid:-unknown}" "${existing_session:-unknown}" "$lock_dir" >&2
    return 1
  fi

  local session_id
  session_id="${reuse_session:-$(hpx_session_id)}"
  printf '%s\n' "$$" >"$pid_file"
  printf '{"session_id":"%s","pid":%s,"started_at":"%s","command":"%s","task_id":"%s"}\n' \
    "$session_id" "$$" "$(date -u +%FT%TZ)" "$command" "$task_id" >"$meta_file"

  printf '%s\n' "$session_id"
  return 0
}

# hpx_lock_force_release <task_id>
# 명시적으로 호출될 때만 lock 제거 (stale override 포함). audit 기록은 호출자 책임.
hpx_lock_force_release() {
  local lock_dir
  lock_dir="$(hpx_lock_dir "$1")"
  rm -rf "$lock_dir"
}

# hpx_lock_release <task_id>
hpx_lock_release() {
  local lock_dir
  lock_dir="$(hpx_lock_dir "$1")"
  rm -rf "$lock_dir"
}

# ---------- State (atomic write) ----------

hpx_state_path() {
  printf 'docs/plans/%s.state.json' "$1"
}

# hpx_state_exists <task_id>
hpx_state_exists() {
  [ -f "$(hpx_state_path "$1")" ]
}

# hpx_state_read <task_id>
# stdout 에 JSON 전체 출력. 없으면 빈 문자열, exit 1.
hpx_state_read() {
  local path
  path="$(hpx_state_path "$1")"
  if [ ! -f "$path" ]; then
    return 1
  fi
  cat "$path"
}

# hpx_state_write <task_id> <json>
# stdin 으로 JSON 받으면 그쪽 우선. 원자적 tmp + mv.
hpx_state_write() {
  local task_id="$1"
  shift
  local path tmp
  path="$(hpx_state_path "$task_id")"
  tmp="${path}.tmp.$$"

  mkdir -p "$(dirname "$path")" >/dev/null 2>&1 || true

  if [ $# -gt 0 ]; then
    printf '%s\n' "$*" >"$tmp"
  else
    cat >"$tmp"
  fi

  if ! python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$tmp" 2>/dev/null; then
    printf 'state_write: invalid JSON, aborting. tmp=%s\n' "$tmp" >&2
    rm -f "$tmp"
    return 1
  fi

  mv "$tmp" "$path"
}

# hpx_run_id_new <command> <session_id> <attempt> [chunk_index]
hpx_run_id_new() {
  local command="$1"
  local session_id="$2"
  local attempt="$3"
  local chunk="${4:-}"
  local ts
  ts="$(hpx_utc_ts)"
  if [ -n "$chunk" ]; then
    printf '%s:%s:%s:%s:c%s' "$command" "$ts" "$session_id" "$attempt" "$chunk"
  else
    printf '%s:%s:%s:%s' "$command" "$ts" "$session_id" "$attempt"
  fi
}

# ---------- Sync context 수집 ----------

# hpx_sync_context
# TASKS / ADR 인덱스 / 최근 git log 를 stdout 에 덤프 (Claude 가 해석).
hpx_sync_context() {
  printf '=== TASKS.md ===\n'
  if [ -f docs/TASKS.md ]; then
    cat docs/TASKS.md
  else
    printf '(missing)\n'
  fi
  printf '\n=== docs/adr/README.md (index) ===\n'
  if [ -f docs/adr/README.md ]; then
    cat docs/adr/README.md
  else
    printf '(missing)\n'
  fi
  printf '\n=== git log --oneline -10 ===\n'
  git log --oneline -10 2>/dev/null || printf '(no git log)\n'
  printf '\n=== git status --short ===\n'
  git status --short 2>/dev/null || printf '(no git status)\n'
}

# ---------- Audit log / metrics ----------

# hpx_audit_append <task_id> <markdown_block>
# markdown_block 는 stdin 으로 받아도 됨.
hpx_audit_append() {
  local task_id="$1"
  local path="docs/plans/.audit/${task_id}.md"
  mkdir -p "$(dirname "$path")" >/dev/null 2>&1 || true
  shift
  {
    if [ $# -gt 0 ]; then
      printf '%s\n\n' "$*"
    else
      cat
      printf '\n'
    fi
  } >>"$path"
}

# 헤더 필요 시 최초 1회만 작성
hpx_metrics_header() {
  printf 'ts\ttask_id\tcommand\trun_id\tloop\tinput_type\tdiff_lines\tinput_bytes\toutput_bytes\tduration_ms\tresult\tfallback_mode\ttokens_in\ttokens_out\tcost_usd\n'
}
hpx_metrics_path() {
  printf '.cache/codex-reviews/_metrics.tsv'
}
# hpx_metrics_append <tsv_line_without_newline>
hpx_metrics_append() {
  local path
  path="$(hpx_metrics_path)"
  mkdir -p "$(dirname "$path")" >/dev/null 2>&1 || true
  if [ ! -f "$path" ]; then
    hpx_metrics_header >"$path"
  fi
  printf '%s\n' "$*" >>"$path"
}

hpx_gate_events_header() {
  printf 'ts\ttask_id\tgate_id\tgate_type\tcommand\trun_id\tshown\tauto_passed\tresult\tuser_choice\tresponse_ms\tdefault_selected\tignored_p0_count\tdegraded_accepted\trisk_level\trisk_signals\treason\n'
}
hpx_gate_events_path() {
  printf '.cache/codex-reviews/gate-events.tsv'
}
# hpx_gate_events_append <tsv_line>
hpx_gate_events_append() {
  local path
  path="$(hpx_gate_events_path)"
  mkdir -p "$(dirname "$path")" >/dev/null 2>&1 || true
  if [ ! -f "$path" ]; then
    hpx_gate_events_header >"$path"
  fi
  printf '%s\n' "$*" >>"$path"
}

# ---------- Codex 응답 헬퍼 ----------

# hpx_extract_tokens_used <stderr_path>
# stderr 의 "tokens used\n<숫자>" 2줄 패턴에서 숫자 추출.
hpx_extract_tokens_used() {
  local stderr_path="$1"
  [ -f "$stderr_path" ] || { printf ''; return; }
  grep -A1 "tokens used" "$stderr_path" 2>/dev/null | tail -1 | tr -d ' \r\n' || printf ''
}

# hpx_json_validate <path>
# exit 0 if valid JSON, else 1
hpx_json_validate() {
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$1" 2>/dev/null
}

# ---------- /work: base branch / diff capture / split / risk ----------

# hpx_base_branch_name
# $PEAKCART_BASE_BRANCH -> git config peakcart.baseBranch -> origin/HEAD -> 'main'
# stdout: base branch **이름** (display/gh pr create --base 용). merge-base 계산 없음.
hpx_base_branch_name() {
  local base_branch
  base_branch="${PEAKCART_BASE_BRANCH:-}"
  base_branch="${base_branch:-$(git config --get peakcart.baseBranch 2>/dev/null)}"
  base_branch="${base_branch:-$(git symbolic-ref --short refs/remotes/origin/HEAD 2>/dev/null | sed 's|^origin/||')}"
  base_branch="${base_branch:-main}"
  printf '%s\n' "${base_branch}"
}

# hpx_base_branch_discover
# stdout: resolved base ref (merge-base SHA 반환, `git diff $BASE` 에서 사용).
# 이름이 필요하면 hpx_base_branch_name.
hpx_base_branch_discover() {
  local base_branch base
  base_branch="$(hpx_base_branch_name)"
  base="$(git merge-base HEAD "origin/${base_branch}" 2>/dev/null \
         || git merge-base HEAD "${base_branch}" 2>/dev/null \
         || printf '%s' "${base_branch}")"
  printf '%s\n' "${base}"
}

# hpx_diff_capture <task_id> <ts>
# git diff <BASE> > .cache/diffs/diff-<task>-<ts>.patch, 경로 echo
# 주의: `git diff` 는 untracked 파일을 포함하지 않으므로, .gitignore 에 걸리지 않은
# untracked 파일을 `git add -N` (intent-to-add) 으로 먼저 등록해 diff 에 포함시킨다.
# 이는 working tree 외 부작용이 없는 안전한 연산이다 (staging 은 하지 않음).
hpx_diff_capture() {
  local task_id="$1"
  local ts="$2"
  local base path
  base="$(hpx_base_branch_discover)"
  path=".cache/diffs/diff-${task_id}-${ts}.patch"
  mkdir -p .cache/diffs >/dev/null 2>&1 || true

  # untracked (non-ignored) 파일을 intent-to-add 으로 마킹
  local untracked
  untracked="$(git ls-files --others --exclude-standard -z 2>/dev/null || true)"
  if [ -n "$untracked" ]; then
    printf '%s' "$untracked" | xargs -0 git add -N -- 2>/dev/null || true
  fi

  git diff "${base}" >"${path}"
  printf '%s\n' "${path}"
}

# hpx_diff_lines <patch_path>
hpx_diff_lines() {
  local p="$1"
  if [ ! -f "$p" ]; then printf '0\n'; return; fi
  wc -l <"$p" | tr -d ' '
}

# hpx_diff_files <patch_path>
# diff --git a/<f> b/<f> 로부터 b 측 파일 목록 추출 (중복 제거)
hpx_diff_files() {
  local p="$1"
  [ -f "$p" ] || return 0
  awk '/^diff --git / { sub(/^b\//,"",$4); print $4 }' "$p" | awk '!seen[$0]++'
}

# hpx_diff_absorption_status <patch_path>
# stdout:
#   all_absorbed   - patch files exist but none are currently uncommitted
#   partially_live - patch files 일부만 현재 uncommitted
#   all_live       - patch files 전부 현재 uncommitted
#   no_files       - patch 에서 파일을 추출할 수 없음
hpx_diff_absorption_status() {
  local patch_path="$1"
  [ -f "$patch_path" ] || { printf 'no_files\n'; return 0; }

  python3 - "$patch_path" <<'PY'
import subprocess, sys
from pathlib import Path

patch = Path(sys.argv[1])
files = []
for line in patch.read_text(encoding="utf-8", errors="ignore").splitlines():
    if line.startswith("diff --git "):
        parts = line.split()
        if len(parts) >= 4:
            path = parts[3]
            if path.startswith("b/"):
                path = path[2:]
            files.append(path)

files = list(dict.fromkeys(files))
if not files:
    print("no_files")
    raise SystemExit(0)

res = subprocess.run(
    ["git", "status", "--porcelain"],
    check=True,
    capture_output=True,
    text=True,
)
uncommitted = set()
for raw in res.stdout.splitlines():
    if len(raw) < 4:
        continue
    path = raw[3:]
    if " -> " in path:
        path = path.split(" -> ", 1)[1]
    uncommitted.add(path)

matched = [f for f in files if f in uncommitted]
if len(matched) == 0:
    print("all_absorbed")
elif len(matched) == len(files):
    print("all_live")
else:
    print("partially_live")
PY
}

# hpx_risk_classify <patch_path>
# stdout: 두 줄 — line1: risk_level (low|medium|high), line2: CSV signals
hpx_risk_classify() {
  local p="$1"
  local lines signals=() files
  lines="$(hpx_diff_lines "$p")"
  files="$(hpx_diff_files "$p" || true)"

  if [ "${lines:-0}" -ge 800 ]; then signals+=("diff_large_800"); fi
  if [ "${lines:-0}" -ge 500 ]; then signals+=("split_review_candidate"); fi

  while IFS= read -r f; do
    [ -z "$f" ] && continue
    case "$f" in
      *auth*|*Auth*|*security*|*Security*|*oauth*|*OAuth*|*jwt*|*Jwt*|*JWT*)
        signals+=("auth_touch"); break;;
    esac
  done <<<"$files"
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    case "$f" in
      *payment*|*Payment*|*billing*|*Billing*|*order*|*Order*)
        signals+=("payment_touch"); break;;
    esac
  done <<<"$files"
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    case "$f" in
      *.yml|*.yaml|*.properties|*.env|infra/*|k8s/*|helm/*|kustomize/*|terraform/*|.github/workflows/*)
        signals+=("config_infra_touch"); break;;
    esac
  done <<<"$files"

  local level="low"
  for s in "${signals[@]:-}"; do
    case "$s" in
      diff_large_800|auth_touch|payment_touch|config_infra_touch) level="high";;
    esac
  done
  if [ "$level" = "low" ] && [ "${#signals[@]}" -gt 0 ]; then
    level="medium"
  fi

  # dedup signals
  local joined=""
  if [ "${#signals[@]}" -gt 0 ]; then
    joined="$(printf '%s\n' "${signals[@]}" | awk '!s[$0]++' | paste -sd, -)"
  fi
  printf '%s\n%s\n' "$level" "${joined}"
}

# hpx_diff_split <patch_path> <out_dir> [max_chunks=3]
# 우선순위: (1) 실행 코드 (2) 테스트 (3) 설정/문서. file 단위로 chunk 할당.
# stdout: 한 줄당 "<chunk_index>\t<chunk_path>\t<file_count>\t<line_count>"
hpx_diff_split() {
  local patch="$1"
  local out_dir="$2"
  local max_chunks="${3:-3}"
  mkdir -p "$out_dir" >/dev/null 2>&1 || true
  python3 - "$patch" "$out_dir" "$max_chunks" <<'PY'
import os, re, sys
patch_path, out_dir, max_chunks = sys.argv[1], sys.argv[2], int(sys.argv[3])
with open(patch_path, 'r', errors='replace') as f:
    data = f.read()
# diff --git a/.. b/.. 기준으로 split
parts = re.split(r'(?m)^(?=diff --git )', data)
blocks = [p for p in parts if p.startswith('diff --git ')]

def classify(block):
    m = re.search(r'^diff --git a/\S+ b/(\S+)', block, re.M)
    fname = m.group(1) if m else ''
    low = fname.lower()
    if re.search(r'(^|/)(src/test/|test/|tests/|__tests__/|.*\.test\.|.*\.spec\.)', low):
        return 2, fname  # tests
    if low.endswith(('.md','.yml','.yaml','.properties','.json','.toml','.ini','.txt')) \
       or low.startswith(('docs/','infra/','k8s/','helm/','kustomize/','terraform/','.github/')):
        return 3, fname  # config/docs
    return 1, fname  # executable code

bucketed = {1: [], 2: [], 3: []}
for b in blocks:
    pr, fname = classify(b)
    bucketed[pr].append((fname, b))

# Flatten by priority, then chunk up to max_chunks by roughly equal line count
ordered = bucketed[1] + bucketed[2] + bucketed[3]
if not ordered:
    sys.exit(0)

total_lines = sum(b.count('\n') for _, b in ordered)
# If only 1 chunk needed or few files: don't split further than necessary
n_chunks = min(max_chunks, max(1, len(ordered)))
# simple greedy pack to balance lines
chunks = [[] for _ in range(n_chunks)]
loads = [0] * n_chunks
for fname, block in ordered:
    idx = loads.index(min(loads))
    chunks[idx].append((fname, block))
    loads[idx] += block.count('\n')

# collapse empty tails
chunks = [c for c in chunks if c]

base = os.path.basename(patch_path).rsplit('.patch', 1)[0]
for i, c in enumerate(chunks, start=1):
    out = os.path.join(out_dir, f'{base}-c{i}.patch')
    with open(out, 'w') as f:
        for _, blk in c:
            f.write(blk)
    fcount = len(c)
    lcount = sum(b.count('\n') for _, b in c)
    print(f'{i}\t{out}\t{fcount}\t{lcount}')
PY
}

# ---------- /ship: consistency precheck / commit plan / PR body ----------

# hpx_consistency_precheck <task_id>
# bash docs/consistency-hints.sh 실행. 결과 3줄 출력:
#   line1: status (ok|warnings|unavailable|script_error)
#   line2: log path (stdout+stderr 합쳐 기록)
#   line3: exit code
# §7-5-E 분기 근거 제공. script 부재는 unavailable (호출자가 skip), exec 실패는 script_error.
hpx_consistency_precheck() {
  local task_id="$1"
  local ts
  ts="$(hpx_epoch_ts)"
  local log_path=".cache/consistency-${task_id}-${ts}.log"
  mkdir -p .cache >/dev/null 2>&1 || true

  if [ ! -f docs/consistency-hints.sh ]; then
    printf 'unavailable\n%s\n0\n' "$log_path"
    return 0
  fi

  bash docs/consistency-hints.sh >"$log_path" 2>&1
  local ec=$?

  local status
  case "$ec" in
    0) status="ok" ;;
    1) status="warnings" ;;
    *) status="script_error" ;;
  esac
  printf '%s\n%s\n%s\n' "$status" "$log_path" "$ec"
}

# hpx_commit_plan_group <patch_path>
# 파일별 분류를 TSV 출력: category\tfile\tlines
# category ∈ {adr, docs, test, chore, src}
# Claude 가 이 TSV 를 읽어 partition_id / scope / subject 결정 (§10-3).
hpx_commit_plan_group() {
  local patch="$1"
  [ -f "$patch" ] || return 1
  python3 - "$patch" <<'PY'
import re, sys
patch = sys.argv[1]
with open(patch, 'r', errors='replace') as f:
    data = f.read()

parts = re.split(r'(?m)^(?=diff --git )', data)
blocks = [p for p in parts if p.startswith('diff --git ')]

def classify(fname):
    low = fname.lower()
    if low.startswith('docs/adr/') and low.endswith('.md'):
        return 'adr'
    if low.startswith('docs/') or low.endswith('.md'):
        return 'docs'
    if re.search(r'(^|/)(src/test/|test/|tests/|__tests__/|.*\.test\.|.*\.spec\.)', low):
        return 'test'
    if (low.endswith(('.yml','.yaml','.properties','.toml','.ini'))
        or low.startswith(('infra/','k8s/','helm/','kustomize/','terraform/','.github/'))
        or low in ('build.gradle','settings.gradle','docker-compose.yml','gradle.properties','package.json','pnpm-lock.yaml','yarn.lock','tsconfig.json')):
        return 'chore'
    return 'src'

for b in blocks:
    m = re.search(r'^diff --git a/\S+ b/(\S+)', b, re.M)
    if not m: continue
    fname = m.group(1)
    lines = b.count('\n')
    cat = classify(fname)
    print(f'{cat}\t{fname}\t{lines}')
PY
}

# hpx_ship_pr_body_data <task_id>
# PR 본문 생성을 위한 데이터 번들을 JSON 으로 출력.
# Claude 가 이 데이터를 §10-2 템플릿에 끼워 본문 작성.
# 포함:
#   - task_id, branch, base_branch
#   - commit_subjects[] (branch vs base 구간의 커밋 subject 목록)
#   - accepted_items[] (state.review_runs[] 마지막 work run 의 accepted_ids 와 해당 id 의 finding/suggestion — 원본 raw JSON 참조)
#   - p0_ignores[] (audit log 에서 "P0 무시 사유" 라인 파싱)
#   - adr_mentions[] (plan.md / diff 에서 등장하는 ADR-NNNN)
hpx_ship_pr_body_data() {
  local task_id="$1"
  local state_path
  state_path="$(hpx_state_path "$task_id")"
  [ -f "$state_path" ] || return 1
  local plan_path="docs/plans/${task_id}.md"
  local audit_path="docs/plans/.audit/${task_id}.md"
  local base_branch
  base_branch="$(hpx_base_branch_name)"
  local current_branch
  current_branch="$(git branch --show-current 2>/dev/null || printf '')"

  python3 - "$task_id" "$state_path" "$plan_path" "$audit_path" "$base_branch" "$current_branch" <<'PY'
import json, os, re, subprocess, sys
task_id, state_path, plan_path, audit_path, base_branch, current_branch = sys.argv[1:7]

with open(state_path, 'r') as f:
    state = json.load(f)

# commits between base_branch and HEAD
commit_subjects = []
try:
    out = subprocess.check_output(
        ['git', 'log', '--pretty=format:%h %s', f'{base_branch}..HEAD'],
        stderr=subprocess.DEVNULL, text=True)
    commit_subjects = [l for l in out.splitlines() if l.strip()]
except Exception:
    pass

# last work run accepted_ids + raw JSON items
accepted_items = []
last_work_run = None
for r in reversed(state.get('review_runs', [])):
    if r.get('command') == 'work':
        last_work_run = r
        break
if last_work_run:
    raw_path = last_work_run.get('raw_path', '')
    accepted_ids = set(last_work_run.get('accepted_ids', []))
    if raw_path and os.path.isfile(raw_path) and accepted_ids:
        try:
            with open(raw_path, 'r') as f:
                raw = json.load(f)
            for it in raw.get('items', []):
                if it.get('id') in accepted_ids:
                    accepted_items.append({
                        'id': it.get('id'),
                        'severity': it.get('severity'),
                        'file': it.get('file'),
                        'line': it.get('line'),
                        'finding': it.get('finding'),
                        'suggestion': it.get('suggestion'),
                    })
        except Exception:
            pass

# P0 ignore reasons from audit log
p0_ignores = []
if os.path.isfile(audit_path):
    with open(audit_path, 'r') as f:
        audit = f.read()
    for m in re.finditer(r'- P0 무시[^\n]*:\s*(.+)', audit):
        p0_ignores.append(m.group(1).strip())

# ADR mentions from plan.md
adr_mentions = []
if os.path.isfile(plan_path):
    with open(plan_path, 'r') as f:
        plan = f.read()
    adr_mentions = sorted(set(re.findall(r'ADR-\d{4}', plan)))

print(json.dumps({
    'task_id': task_id,
    'branch': current_branch,
    'base_branch': base_branch,
    'commit_subjects': commit_subjects,
    'accepted_items': accepted_items,
    'p0_ignores': p0_ignores,
    'adr_mentions': adr_mentions,
    'completed_plan_items': state.get('completed_plan_items', []),
}, ensure_ascii=False, indent=2))
PY
}

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

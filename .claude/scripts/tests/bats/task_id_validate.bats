#!/usr/bin/env bats
# Tests for hpx_task_id_validate (task-d011 P1).
# Allowlist: [A-Za-z0-9._-]+, length 1..128, no ".." substring,
# no leading "-" or "." (avoids option/dotfile confusion).

setup() {
  REPO_ROOT="$(git rev-parse --show-toplevel)"
  cd "$REPO_ROOT"
  # shellcheck disable=SC1091
  source .claude/scripts/shared-logic.sh
}

# ---- Positive: representative static cases -----------------------------------

@test "task_id_validate: accepts simple kebab-case (task-foo)" {
  run hpx_task_id_validate "task-foo"
  [ "$status" -eq 0 ]
}

@test "task_id_validate: accepts current task_id (task-d011-harness-hardening)" {
  run hpx_task_id_validate "task-d011-harness-hardening"
  [ "$status" -eq 0 ]
}

@test "task_id_validate: accepts dots and underscores (a.b_c-1)" {
  run hpx_task_id_validate "a.b_c-1"
  [ "$status" -eq 0 ]
}

# ---- Positive: regression guard for existing done/ basenames -----------------

@test "task_id_validate: every docs/plans/done/*.md basename is accepted" {
  shopt -s nullglob || true
  local count=0
  for f in docs/plans/done/*.md; do
    local base
    base="$(basename "${f%.md}")"
    run hpx_task_id_validate "$base"
    [ "$status" -eq 0 ] || {
      echo "rejected: $base ($f)"
      return 1
    }
    count=$((count + 1))
  done
  # Sanity: ensure we actually looped (avoids silent pass on empty glob).
  [ "$count" -ge 1 ]
}

# ---- Negative: structural rejections -----------------------------------------

@test "task_id_validate: empty string rejected" {
  run hpx_task_id_validate ""
  [ "$status" -ne 0 ]
  [[ "$output" == *"empty"* ]]
}

@test "task_id_validate: traversal '../foo' rejected" {
  run hpx_task_id_validate "../foo"
  [ "$status" -ne 0 ]
}

@test "task_id_validate: 'foo/..' rejected (slash + dotdot)" {
  run hpx_task_id_validate "foo/.."
  [ "$status" -ne 0 ]
}

@test "task_id_validate: 'foo..bar' rejected (dotdot substring)" {
  run hpx_task_id_validate "foo..bar"
  [ "$status" -ne 0 ]
  [[ "$output" == *".."* ]]
}

@test "task_id_validate: leading dash '-foo' rejected" {
  run hpx_task_id_validate "-foo"
  [ "$status" -ne 0 ]
}

@test "task_id_validate: leading dot '.foo' rejected" {
  run hpx_task_id_validate ".foo"
  [ "$status" -ne 0 ]
}

@test "task_id_validate: space inside 'foo bar' rejected" {
  run hpx_task_id_validate "foo bar"
  [ "$status" -ne 0 ]
}

@test "task_id_validate: length > 128 rejected" {
  local long
  long="$(printf 'a%.0s' {1..129})"
  run hpx_task_id_validate "$long"
  [ "$status" -ne 0 ]
  [[ "$output" == *"too long"* ]]
}

@test "task_id_validate: length == 128 accepted (boundary)" {
  local exact
  exact="$(printf 'a%.0s' {1..128})"
  run hpx_task_id_validate "$exact"
  [ "$status" -eq 0 ]
}

# ---- Negative: forbidden characters -----------------------------------------

@test "task_id_validate: 'foo/bar' rejected (slash)" {
  run hpx_task_id_validate "foo/bar"
  [ "$status" -ne 0 ]
}

@test "task_id_validate: 'foo;rm' rejected (semicolon)" {
  run hpx_task_id_validate "foo;rm"
  [ "$status" -ne 0 ]
}

@test "task_id_validate: 'foo\$x' rejected (dollar)" {
  run hpx_task_id_validate 'foo$x'
  [ "$status" -ne 0 ]
}

@test "task_id_validate: tab inside rejected" {
  run hpx_task_id_validate $'foo\tbar'
  [ "$status" -ne 0 ]
}

@test "task_id_validate: newline inside rejected" {
  run hpx_task_id_validate $'foo\nbar'
  [ "$status" -ne 0 ]
}

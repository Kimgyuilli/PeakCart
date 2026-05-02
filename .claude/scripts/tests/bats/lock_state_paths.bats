#!/usr/bin/env bats
# Tests for sanitize integration into lock/state helpers (task-d011 P2).
# Verifies that malicious task_id values produce zero filesystem side effects.

setup() {
  REPO_ROOT="$(git rev-parse --show-toplevel)"
  cd "$REPO_ROOT"
  # shellcheck disable=SC1091
  source .claude/scripts/shared-logic.sh

  # Sentinel — must remain intact even if a helper attempts a traversal rm.
  SENTINEL="${BATS_TEST_TMPDIR}/sentinel"
  echo "i exist" >"$SENTINEL"

  # Use an isolated task_id for positive cases so we never collide with real
  # state files. Keep length sane and within allowlist.
  TID="task-bats-${BATS_TEST_NUMBER:-0}"
}

teardown() {
  # Best-effort cleanup of any state/lock created for the positive task_id.
  if [ -n "${TID:-}" ]; then
    rm -rf "docs/plans/${TID}.lock" "docs/plans/${TID}.state.json" 2>/dev/null || true
  fi
}

# ---- Positive: idempotent re-enter on hpx_lock_acquire -----------------------

@test "lock_acquire: same session_id re-enter is idempotent" {
  run hpx_lock_acquire "$TID" plan
  [ "$status" -eq 0 ]
  local sid="$output"

  run hpx_lock_acquire "$TID" plan "$sid"
  [ "$status" -eq 0 ]
  [ "$output" = "$sid" ]

  hpx_lock_force_release "$TID"
}

@test "lock_force_release: removes lock dir for valid task_id" {
  hpx_lock_acquire "$TID" plan >/dev/null
  [ -d "docs/plans/${TID}.lock" ]
  hpx_lock_force_release "$TID"
  [ ! -e "docs/plans/${TID}.lock" ]
}

# ---- Negative: path-builder helpers reject bad ids ---------------------------

@test "lock_dir: '../etc' rejected, no path created" {
  run hpx_lock_dir "../etc"
  [ "$status" -ne 0 ]
  [ ! -e "/tmp/etc.lock" ]
  [ ! -e "docs/plans/../etc.lock" ]
}

@test "state_path: '../foo' rejected, no file created" {
  run hpx_state_path "../foo"
  [ "$status" -ne 0 ]
  [ ! -e "docs/plans/../foo.state.json" ]
}

# ---- Negative: top-level helpers reject bad ids + no side effects ----------

@test "lock_acquire: 'foo;rm' rejected, no mkdir" {
  run hpx_lock_acquire "foo;rm" plan
  [ "$status" -ne 0 ]
  [ ! -e "docs/plans/foo;rm.lock" ]
}

@test "lock_force_release: '../etc' rejected, sentinel intact" {
  run hpx_lock_force_release "../etc"
  [ "$status" -ne 0 ]
  [ -f "$SENTINEL" ]
  # And the canonical traversal target was not produced/removed.
  [ ! -e "docs/plans/../etc.lock" ]
}

@test "state_write: '../foo' rejected, no file created" {
  run hpx_state_write "../foo" '{}'
  [ "$status" -ne 0 ]
  [ ! -e "docs/plans/../foo.state.json" ]
}

@test "state_read: '../foo' rejected, no read" {
  run hpx_state_read "../foo"
  [ "$status" -ne 0 ]
}

@test "state_exists: '../foo' rejected (not a silent false)" {
  run hpx_state_exists "../foo"
  [ "$status" -ne 0 ]
}

@test "state_exists: legitimate but missing id returns 1 cleanly" {
  run hpx_state_exists "task-bats-nonexistent-xyz"
  [ "$status" -ne 0 ]
}

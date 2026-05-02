#!/usr/bin/env bats
# Tests for scripts/timeout_wrapper.py seconds validation (task-d011 P4).
#
# Rejection contract: exit 2 + stderr containing "invalid seconds:".
#   Covers: 0, negative, NaN, +/-Inf, 1e309 (parses to inf), non-numeric, empty.
# Positive contract: passes through to GNU timeout(1)-like behavior.

setup() {
  REPO_ROOT="$(git rev-parse --show-toplevel)"
  cd "$REPO_ROOT"
  WRAPPER="${REPO_ROOT}/scripts/timeout_wrapper.py"
}

# ---- Rejection cases (exit 2 + "invalid seconds:" stderr) -------------------

@test "timeout_wrapper: 0 rejected" {
  run python3 "$WRAPPER" 0 sleep 0
  [ "$status" -eq 2 ]
  [[ "$output" == *"invalid seconds:"* ]]
}

@test "timeout_wrapper: -1 rejected" {
  run python3 "$WRAPPER" -1 sleep 0
  [ "$status" -eq 2 ]
  [[ "$output" == *"invalid seconds:"* ]]
}

@test "timeout_wrapper: -0.5 rejected" {
  run python3 "$WRAPPER" -0.5 sleep 0
  [ "$status" -eq 2 ]
  [[ "$output" == *"invalid seconds:"* ]]
}

@test "timeout_wrapper: NaN rejected" {
  run python3 "$WRAPPER" NaN sleep 0
  [ "$status" -eq 2 ]
  [[ "$output" == *"invalid seconds:"* ]]
}

@test "timeout_wrapper: nan rejected" {
  run python3 "$WRAPPER" nan sleep 0
  [ "$status" -eq 2 ]
  [[ "$output" == *"invalid seconds:"* ]]
}

@test "timeout_wrapper: Inf rejected" {
  run python3 "$WRAPPER" Inf sleep 0
  [ "$status" -eq 2 ]
  [[ "$output" == *"invalid seconds:"* ]]
}

@test "timeout_wrapper: +Inf rejected" {
  run python3 "$WRAPPER" +Inf sleep 0
  [ "$status" -eq 2 ]
  [[ "$output" == *"invalid seconds:"* ]]
}

@test "timeout_wrapper: Infinity rejected" {
  run python3 "$WRAPPER" Infinity sleep 0
  [ "$status" -eq 2 ]
  [[ "$output" == *"invalid seconds:"* ]]
}

@test "timeout_wrapper: 1e309 (parses to inf) rejected" {
  run python3 "$WRAPPER" 1e309 sleep 0
  [ "$status" -eq 2 ]
  [[ "$output" == *"invalid seconds:"* ]]
}

@test "timeout_wrapper: abc rejected" {
  run python3 "$WRAPPER" abc sleep 0
  [ "$status" -eq 2 ]
  [[ "$output" == *"invalid seconds:"* ]]
}

@test "timeout_wrapper: empty arg rejected" {
  run python3 "$WRAPPER" "" sleep 0
  [ "$status" -eq 2 ]
  [[ "$output" == *"invalid seconds:"* ]]
}

# ---- Positive cases ----------------------------------------------------------

@test "timeout_wrapper: small valid timeout passes through (0.5 sleep 0 → 0)" {
  # NOTE: plan §P12 originally specified 0.001 here, but Python subprocess
  # spawn overhead alone exceeds 1ms — the wrapper would race and return 124
  # for tiny timeouts. 0.5 is unambiguous: sleep 0 always returns well under
  # 500ms, so exit 0 is deterministic.
  run python3 "$WRAPPER" 0.5 sleep 0
  [ "$status" -eq 0 ]
}

@test "timeout_wrapper: 1 sleep 0.1 → exit 0 (no timeout fires)" {
  run python3 "$WRAPPER" 1 sleep 0.1
  [ "$status" -eq 0 ]
}

@test "timeout_wrapper: 1 sleep 5 → exit 124 (timeout)" {
  run python3 "$WRAPPER" 1 sleep 5
  [ "$status" -eq 124 ]
}

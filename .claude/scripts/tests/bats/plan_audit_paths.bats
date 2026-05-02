#!/usr/bin/env bats
# Tests that hpx_plan_lint and hpx_audit_append refuse traversal task_ids.
# (task-d011 P2)

setup() {
  REPO_ROOT="$(git rev-parse --show-toplevel)"
  cd "$REPO_ROOT"
  # shellcheck disable=SC1091
  source .claude/scripts/shared-logic.sh
}

@test "plan_lint: '../etc/passwd' rejected" {
  run hpx_plan_lint "../etc/passwd"
  [ "$status" -ne 0 ]
}

@test "plan_lint: rejects task_id without reading any file" {
  # Even if a same-named file existed somewhere accessible, validation must
  # short-circuit before any read. We assert by attempting a path that would
  # be a clear traversal target.
  run hpx_plan_lint "../../tmp/peekcart-bats-should-not-read"
  [ "$status" -ne 0 ]
}

@test "audit_append: '../foo' rejected, no file created" {
  local target="docs/plans/.audit/../foo.md"
  run hpx_audit_append "../foo" "must not write"
  [ "$status" -ne 0 ]
  [ ! -e "$target" ]
}

@test "audit_append: '.hidden' rejected (leading dot)" {
  run hpx_audit_append ".hidden" "must not write"
  [ "$status" -ne 0 ]
  [ ! -e "docs/plans/.audit/.hidden.md" ]
}

@test "audit_append: 'foo;rm' rejected" {
  run hpx_audit_append "foo;rm" "must not write"
  [ "$status" -ne 0 ]
  [ ! -e "docs/plans/.audit/foo;rm.md" ]
}

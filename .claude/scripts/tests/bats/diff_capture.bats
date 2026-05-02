#!/usr/bin/env bats
# Tests for hpx_diff_capture isolation (task-d011 P6).
#
# Verifies:
#   - .git/index hash is unchanged after capture (no `git add -N` side effect)
#   - git status output is unchanged
#   - diff includes: staged modified, staged new, untracked, special-name untracked
#   - works on a fresh repo with no .git/index (unborn case)
#   - task_id validation rejects traversal

REPO_ROOT=""
SHARED_LOGIC=""

setup() {
  REPO_ROOT="$(git rev-parse --show-toplevel)"
  SHARED_LOGIC="${REPO_ROOT}/.claude/scripts/shared-logic.sh"

  # Build a sandbox repo for each test. cd into it so hpx_diff_capture's
  # relative paths (.cache/diffs/) resolve inside the sandbox.
  SANDBOX="${BATS_TEST_TMPDIR}/repo"
  mkdir -p "$SANDBOX"
  cd "$SANDBOX"

  # Force base branch to 'main' (no origin in sandbox).
  export PEAKCART_BASE_BRANCH=main

  # shellcheck disable=SC1090
  source "$SHARED_LOGIC"
}

teardown() {
  unset PEAKCART_BASE_BRANCH
}

# Helper: initialize a sandbox repo with a 'main' branch + 1 commit.
_init_repo_with_main() {
  git init -q -b main .
  git config user.email "bats@example.com"
  git config user.name "bats"
  echo "base" >base.txt
  git add base.txt
  git -c commit.gpgsign=false commit -q -m "init"
}

# ---- Negative: task_id validation -------------------------------------------

@test "diff_capture: rejects '../etc' task_id" {
  _init_repo_with_main
  run hpx_diff_capture "../etc" "0"
  [ "$status" -ne 0 ]
}

# ---- Positive: isolation + content correctness ------------------------------

@test "diff_capture: .git/index hash and git status unchanged after capture" {
  _init_repo_with_main

  # Branch off main, make changes.
  git checkout -q -b topic
  echo "modified" >>base.txt   # staged modified candidate (will git add)
  git add base.txt
  echo "new staged" >c.txt
  git add c.txt                # staged new
  echo "untracked" >b.txt           # untracked
  printf 'with space' >"b c.txt"    # space in filename
  printf 'with newline' >"$(printf 'b\nc.txt')"  # newline in filename — NUL pipeline regression guard

  local before_hash before_status
  before_hash="$(shasum -a 256 .git/index | awk '{print $1}')"
  # Exclude .cache/ — capture writes its own output there as a side-effect of
  # this very test. The invariant we care about is that pre-existing tracked /
  # untracked entries are unchanged.
  before_status="$(git status --porcelain | grep -v '^?? \.cache/' || true)"

  local diff_path
  diff_path="$(hpx_diff_capture "task-bats-diff" "$(hpx_epoch_ts)")"
  [ -f "$diff_path" ]

  local after_hash after_status
  after_hash="$(shasum -a 256 .git/index | awk '{print $1}')"
  after_status="$(git status --porcelain | grep -v '^?? \.cache/' || true)"

  [ "$before_hash" = "$after_hash" ]
  [ "$before_status" = "$after_status" ]
}

@test "diff_capture: includes staged modified, staged new, untracked" {
  _init_repo_with_main
  git checkout -q -b topic

  echo "modified" >>base.txt
  git add base.txt
  echo "new staged" >c.txt
  git add c.txt
  echo "untracked content" >b.txt

  local diff_path
  diff_path="$(hpx_diff_capture "task-bats-diff" "$(hpx_epoch_ts)")"

  grep -q "^+++ b/base.txt" "$diff_path"   # staged modified
  grep -q "^+++ b/c.txt"    "$diff_path"   # staged new (read-tree HEAD regression guard)
  grep -q "^+++ b/b.txt"    "$diff_path"   # untracked
  grep -q "untracked content" "$diff_path"
}

@test "diff_capture: untracked filename with newline included (NUL pipeline)" {
  _init_repo_with_main
  git checkout -q -b topic

  # Newline in filename — only NUL-delimited pipelines handle this safely.
  # macOS bash 3.2 supports $'...' ANSI-C quoting, so this works in Bats too.
  local nl_name
  nl_name="$(printf 'b\nc.txt')"
  printf 'newline content' >"$nl_name"

  local diff_path
  diff_path="$(hpx_diff_capture "task-bats-diff-nl" "$(hpx_epoch_ts)")"

  # Some git versions quote the path; others escape with octal. Match the
  # body content rather than the header to avoid both flavors.
  grep -q "newline content" "$diff_path"
}

@test "diff_capture: works on unborn repo (no .git/index)" {
  # Fresh repo, no commits, no index.
  git init -q -b main .
  git config user.email "bats@example.com"
  git config user.name "bats"
  [ ! -f .git/index ]

  echo "first" >a.txt   # untracked, no commits yet

  # Without a 'main' commit, hpx_base_branch_discover falls back to literal
  # 'main' — git diff main will fail. We accept either: capture succeeds with
  # empty/limited diff, OR returns non-zero. Key invariant: NO side effect
  # on a non-existent .git/index (must not be created by helper).
  run hpx_diff_capture "task-bats-unborn" "$(hpx_epoch_ts)"

  # The helper itself must not crash the shell environment. Index should
  # still not exist (we never staged anything).
  [ ! -f .git/index ]
}

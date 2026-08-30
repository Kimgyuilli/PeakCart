#!/usr/bin/env bats
# hpx_plan_lint 가 bash·zsh 양쪽에서 실행되는지 검증한다.
# 회귀 대상: `local path=` 는 zsh 에서 특수 배열 `path`(=PATH)를 덮어
# 바로 다음 줄의 python3 를 command not found 로 만든다. (구현 ⑤ P10)

setup() {
  REPO_ROOT="$(git rev-parse --show-toplevel)"
  cd "$REPO_ROOT"
  # shellcheck disable=SC1091
  source .claude/scripts/shared-logic.sh
}

@test "plan_lint: bash 에서 유효한 계획서에 OK" {
  run hpx_plan_lint "task-impl5-cqrs-cache-fallback"
  [ "$status" -eq 0 ]
  [[ "$output" == *"OK"* ]]
}

@test "plan_lint: zsh 에서도 동일하게 OK (PATH 오염 회귀)" {
  if ! command -v zsh >/dev/null 2>&1; then
    skip "zsh 미설치"
  fi
  run zsh -c 'source .claude/scripts/shared-logic.sh; hpx_plan_lint "task-impl5-cqrs-cache-fallback"'
  [ "$status" -eq 0 ]
  [[ "$output" == *"OK"* ]]
}

@test "plan_lint: python3 에 전달되는 인자가 대상 계획서 한 경로다" {
  # python3 를 가로채 전달 인자를 고정한다. 호출 형태는 `python3 - "$plan_path"` 이므로
  # 셸이 보는 인자는 ("-", 계획서경로) 2개이고, python 의 sys.argv[1] 이 그 경로가 된다.
  # 선언만 고치고 참조(`$path`)를 남기면 빈 인자 또는 PATH 배열이 넘어가 여기서 걸린다.
  local shim
  shim="$(mktemp -d)"
  cat > "$shim/python3" <<'SHIM'
#!/usr/bin/env bash
cat >/dev/null          # stdin 의 heredoc 스크립트는 버린다
echo "ARGC=$#"
echo "ARGV1=$1"
echo "ARGV2=$2"
SHIM
  chmod +x "$shim/python3"

  run env PATH="$shim:$PATH" bash -c 'source .claude/scripts/shared-logic.sh; hpx_plan_lint "task-impl5-cqrs-cache-fallback"'
  rm -rf "$shim"

  [[ "$output" == *"ARGC=2"* ]]
  [[ "$output" == *"ARGV1=-"* ]]
  [[ "$output" == *"ARGV2=docs/plans/task-impl5-cqrs-cache-fallback.md"* ]]
}

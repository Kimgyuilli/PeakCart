# task-ship-smoke-fresh — `/ship --execute` fresh-state smoke fixture

> 작성: 2026-04-21
> 관련 Phase: Phase 4a
> 관련 ADR: ADR-0001
>
> **주의**: 이 문서는 Claude × Codex 하네스의 `/ship --execute` 재진입 검증용 smoke fixture 이다.
> 실제 PeakCart 기능 요구사항이 아니라, 작은 변경을 안전하게 만들고 되돌리는 절차를 검증하는 것이 목적이다.

## 1. 목표

fresh `work.done` state 를 의도적으로 만들고 `/ship --execute`의 commit, push, PR, archive 경로를 검증한다.
변경 범위는 작고 독립적이어야 하며, 종료 후 `PR close + revert + archive`로 완전히 정리 가능해야 한다.

## 2. 후보 선정 이유

- 기존 `task-work-smoke` 는 이미 git history 에 흡수되어 stale state 로 archive 되었으므로 재사용하지 않는다.
- 이번 smoke 는 **새 task id + 새 파일 경로**를 사용해 state/diff drift 없이 `work.done` → `/ship` 을 검증한다.
- 변경 위치를 `global.cache` 의 작은 공용 유틸로 제한해 도메인 로직/인프라 설정을 건드리지 않는다.

## 3. 작업 항목

- [ ] **P1.** `src/main/java/com/peekcart/global/cache/HarnessShipFreshWindow.java` 생성
  - 시그니처: `Duration clampPositive(Duration ttl, Duration maxWindow)`
  - 동작:
    - `ttl`, `maxWindow` 는 `null` 금지
    - `ttl` 이 음수면 `Duration.ZERO` 반환
    - `ttl` 이 `maxWindow` 보다 크면 `maxWindow` 반환
    - 그 외에는 `ttl` 그대로 반환
- [ ] **P2.** 동일 클래스에 `slf4j Logger INFO` 1회 기록
  - `System.out` 금지
  - 로그 값: `ttl`, `maxWindow`, `resolved`
- [ ] **P3.** `src/test/java/com/peekcart/global/cache/HarnessShipFreshWindowTest.java` 작성
  - 정상 케이스: `ttl < maxWindow`
  - 상한 clamp 케이스: `ttl > maxWindow`
  - 음수 케이스: `ttl < 0`
  - null 입력 2종: `ttl`, `maxWindow`

## 4. 영향 파일

| 파일 | 종류 | 변경 요지 |
|------|------|----------|
| `src/main/java/com/peekcart/global/cache/HarnessShipFreshWindow.java` | 신규 | 작은 순수 유틸 추가 |
| `src/test/java/com/peekcart/global/cache/HarnessShipFreshWindowTest.java` | 신규 | smoke 검증용 단위 테스트 추가 |

## 5. 검증 방법

- `/plan` 으로 task id / 계획서 참조 확인
- `/work` 종료 후 active state 가 `stage=work.done`, `last_diff_path` 존재, working tree dirty 인지 확인
- `/ship --execute` 에서 commit partition 이 `src` / `test` 로 분리되는지 확인
- 필요 시 `./gradlew test --tests com.peekcart.global.cache.HarnessShipFreshWindowTest` 로 국소 검증

## 6. 완료 조건

- 새 task id 로 `work.done` state 생성
- `/ship --execute` 가 stale-state guard 에 걸리지 않고 Step 4 이후로 진행
- 종료 후 PR close, revert, archive 까지 정리 가능

## 7. 비고

- `task-work-smoke` 와 구분하기 위해 파일명과 task id 모두 새로 잡는다.
- smoke 종료 후 merge 금지. 항상 revert 후 archive 보존.

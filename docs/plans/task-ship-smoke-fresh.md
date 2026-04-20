# task-ship-smoke-fresh — `/ship --execute` fresh-state smoke fixture

> 작성: 2026-04-21
> 분류: **Phase 로드맵 외 — Claude × Codex 하네스 smoke fixture** (Phase Exit Criteria 대상 아님)
> 관련 ADR: ADR-0001 (레이어 경계 준수만 확인), ADR-0002 (Phase 경계 — 본 문서는 해당 경계 밖)
>
> **주의**: 이 문서는 `/ship --execute` 재진입 경로 검증용 fixture 이다. PeekCart Phase 1~4 기능
> 요구사항이 아니며, Phase Exit Criteria 체크리스트에 포함되지 않는다. 성공 기준은 "작은 변경을 안전하게
> 만들고 되돌릴 수 있음" 이다.

## 1. 목표

fresh `work.done` state 를 의도적으로 만들고 `/ship --execute`의 commit, push, PR, archive 경로를 검증한다.
변경 범위는 작고 독립적이어야 하며, 종료 후 `PR close + revert + archive`로 완전히 정리 가능해야 한다.

## 2. 후보 선정 이유

- 기존 `task-work-smoke` 는 이미 git history 에 흡수되어 stale state 로 archive 되었으므로 재사용하지 않는다.
- 이번 smoke 는 **새 task id + 새 파일 경로**를 사용해 state/diff drift 없이 `work.done` → `/ship` 을 검증한다.
- 변경 위치를 `global.cache` 의 작은 공용 유틸로 제한해 도메인 로직/인프라 설정을 건드리지 않는다.

## 2-1. 트레이드오프 / 의도된 비대상

**대안 비교**
- (A) 기존 도메인 서비스 내 유틸 추가 → 도메인 경계 오염, smoke 종료 시 revert 가 도메인 코드까지 건드려 risk 증가. 기각.
- (B) 테스트 리소스 하위 dummy 파일만 추가 → `commit_plan` 이 `src` / `test` 로 분리되지 않아 `/ship --execute` Step 4 partition guard 검증 불가. 기각.
- (C) **채택**: `global.cache` 의 순수 Java 유틸 + 단위 테스트 → 외부 의존 0, 레이어 경계 안전, commit partition 확보.

**의도된 비대상** (누락이 아닌 명시적 out-of-scope)
- Flyway 마이그레이션: 신규 테이블/컬럼 없음
- 신규 ADR: ADR-0001 / ADR-0002 범위 내 유틸 추가
- 아키텍처 문서 업데이트 (`docs/02-architecture.md` 등): 레이어 규칙 변동 없음
- 도메인 로직 / 이벤트 토픽 영향: 없음

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

### 5-1. 필수 코드 게이트 (반드시 green)
- `./gradlew test --tests com.peekcart.global.cache.HarnessShipFreshWindowTest`
- 기대 어설션
  - 정상: `ttl < maxWindow` → `ttl` 반환
  - 상한 clamp: `ttl > maxWindow` → `maxWindow` 반환
  - 음수: `ttl < Duration.ZERO` → `Duration.ZERO` 반환
  - null 입력 2종: `ttl == null` / `maxWindow == null` → `NullPointerException` (`Objects.requireNonNull` 기반, 메시지 `"ttl"` / `"maxWindow"` 일치)
  - 로그: `Logback ListAppender` 등으로 캡처 후 INFO 이벤트 정확히 1건, 메시지에 `ttl`, `maxWindow`, `resolved` 세 값이 모두 포함됨을 어설션

### 5-2. 하네스 상태 게이트
- `/plan` 종료: `stage=plan.done`, `review_runs[].result=ok` 최소 1건
- `/work` 종료: `stage=work.done`, `last_diff_path` 존재, working tree dirty
- `/ship --execute` 성공 판정 (모두 충족)
  - `commit_plan` 이 `src` / `test` 로 분리되고 `created_commits` 에 2건 append
  - `push_status=ok`, `remote_branch` non-null
  - `pr_url` non-null (gh CLI 로 PR 생성)
  - stale-state guard 미발동 (Step 4 이후로 정상 진행)

## 6. 완료 조건

- §5-1 테스트 전부 green
- §5-2 상태 게이트 전부 충족
- 종료 후 PR close → revert → archive 까지 정리 가능 (merge 금지)

## 7. 비고

- `task-work-smoke` 와 구분하기 위해 파일명과 task id 모두 새로 잡는다.
- smoke 종료 후 merge 금지. 항상 revert 후 archive 보존.

---

## Audit log

### 2026-04-21 05:10 — GP-2 (loop 2)
- 리뷰 항목: 3건 (P0:0, P1:2, P2:1)
- 사용자 선택: [2] 전체 반영
- 반영 내역
  - finding#1 (P1) → 머리말 분류 수정: "Phase 로드맵 외 smoke fixture" 명시, Phase Exit Criteria 대상 아님을 선언
  - finding#2 (P1) → §5 검증 방법을 §5-1 코드 게이트 / §5-2 상태 게이트로 분할, `./gradlew test` 를 필수로 승격하고 null 입력 기대 예외·로그 어설션·`/ship` 성공 판정 기준을 고정
  - finding#3 (P2) → §2-1 트레이드오프 / 의도된 비대상 섹션 신설 (대안 A/B 기각 사유 + Flyway/ADR/아키 문서 비대상 명시)
- 앞선 loop 1 은 codex 60s timeout (result=timeout) 으로 loop 2 재시도됨
- raw: .cache/codex-reviews/plan-task-ship-smoke-fresh-1776715704.json
- run_id: plan:20260420T200824Z:ae97f975-d328-48d8-ac0d-5d2083318b55:2

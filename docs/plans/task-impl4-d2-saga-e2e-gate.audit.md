# ④-d-2 계획 리뷰 audit

## 2026-08-27 13:59 — 계획 리뷰 라운드 1
- 항목: 13건 (P0:1, P1:9, P2:3)
- 처리: 반영 13건 / 기각 0건
- 뒤집힌 전제:
  - V8 — `testsuite@name` 은 classname 이 아니라 클래스 `@DisplayName`. FQCN 은 `testcase@classname`
  - V9 — 업무 consumer 클래스 8 → **9**. group 상수가 다음 줄에 있는 케이스로 grep 대조 불가
  - V6 — `Save image for publish` 도 push 전용이라 upload 조건만 제거하면 PR 에 파일 자체가 없음
- 결정: P0(④ 종결 자기모순) → PG stub 도입으로 환불 체인 전구간을 범위에 넣고 ④ 종결 유지 (사용자 승인)
- 신규 항목: P15(PG stub) · P16(음성 대조군 상시) · P17(스케줄러 배선) · P18(Flyway cold start)
- raw: `.cache/codex-reviews/plan-task-impl4-d2-saga-e2e-gate-1787806325.json`

## 2026-08-27 — 계획 리뷰 라운드 2
- 항목: 11건 (P0:0, P1:10, P2:1)
- 처리: 반영 10건 / 기각 1건
- 기각: R2 #7(`base-url` 필수값화) — "환경 불변 단일 값" 근거. **R3 에서 철회됨**
- 뒤집힌 전제:
  - V16 — outbox 행은 이미 직렬화된 문자열. SQL INSERT seed 는 publisher 직렬화를 **여전히 우회**
  - V17 — `DlqTopology` 의 group 은 업무 실패 소유자 group 이지 DLQ intake listener group 이 아님
  - V18 — `ALREADY_CANCELED`·reconciliation 이 `GET /payments/{key}` 를 항상 먼저 호출
  - V20 — `lockAtLeastFor=PT30S` 선발화가 짧은 override 를 무효화
  - R1 이 상시 승격한 음성 대조군에서 **가장 중요한 poller 정지 대조군이 빠져 있었음**(N11 과 모순)
  - P18 cold start 판정식이 warm reuse 를 구별 못 함
- 신규 항목: P19(시나리오 격리) · P20(실행 예산)

## 2026-08-27 — 계획 리뷰 라운드 3 (최종)
- 항목: 12건 (P0:0, P1:10, P2:2)
- 처리: 반영 12건 / 기각 0건 (R2 기각 1건은 **철회**)
- 뒤집힌 전제:
  - R2 #7 기각 논거가 **내 계획서 자체에 의해 반증** — 운영 URL 과 stub URL 로 값이 갈리므로 `base-url` 은 환경별 연결 정보
  - `DlqTopology` 철회가 과잉교정 — 업무 구독 21쌍은 이미 그 안에 있어 신설 시 **이중 정본**
  - `run_id` marker 분기가 **도달 불가** — warm datadir 은 initdb 스크립트를 재실행하지 않음
  - `payment.failed` 소비자는 3곳. **Payment 는 자기 이벤트를 소비하지 않아** `processed_events` 행이 생기지 않음
  - P18/P19 상호 모순 + `image-contract-lint` 가 matrix 6 을 강제
  - **`hpx_plan_lint` 실제 위반** — 등장 순서 P1..Pn 강제 + `목표/목적`·`영향 파일` 필수 섹션
- 조치: 전면 재번호 P1~P20(등장 순서), §4 영향 파일 신설, `✗` 검증 행 0으로, lint 직접 실행 통과
- 수렴 판정: **P1 = 0 이 아니므로 상한(3회) 도달로 종료.** 잔여 위험은 §9 미충족 + 이 audit 에 기록

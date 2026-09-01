# audit — `task-impl4-c2b-dlq-replay` 계획 리뷰

> 계획서: `docs/plans/task-impl4-c2b-dlq-replay.md`
> 정정 내용(무엇이 왜 틀렸나)은 계획서 §11 에 있다. 여기에는 **라운드별 집계와 raw 경로**만 남긴다.

---

## 2026-09-02 — 계획 리뷰 라운드 1

- 항목: **16건** (P0:0, P1:15, P2:1)
- 처리: **반영 16건 / 기각 0건**
- 뒤집힌 전제:
  - deadline clamp 를 `now + clockSkewBudget` 로 적었으나 ADR-0020 §D5-3 은 **`now` 로 clamp** 를 명시 — 초안이 ADR 과 충돌
  - "`save` 스텁 예외 1회로 중복 발행 관측" — `OutboxPollingService:116` 이 **같은 객체를 다시 save** 하므로 재발행이 일어나지 않는다. 검증이 주장을 관측하지 못하는 설계였다
  - "E2E 무영향" — `scripts/e2e/saga_e2e.py:64-68` 이 Flyway **버전 집합 정확 일치**를 readiness 에서 강제한다
  - `outbox_event_id` 를 조건부 UPDATE 조건에 넣음 — auto-increment 라 INSERT 후에만 존재
- 구조 변화: PR 순서를 **뒤집었다** — 상관·재개방을 진입점보다 먼저 배포 (2b-3 ↔ 2b-4)
- raw: `.cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-1788277701.json`

## 2026-09-02 — 계획 리뷰 라운드 2

- 항목: **14건** (P0:0, P1:9, P2:5) — 이 중 **8건이 1R 수정이 만든 새 결함**
- 처리: **반영 14건 / 기각 0건**
- 뒤집힌 전제:
  - `AND publication_status <> 'REQUESTED'` — SQL 에서 `NULL <> 'x'` 는 **UNKNOWN** 이라 `NULL` 행(절대다수)이 종결 불가가 된다. ADR §D6-2b 표와 정면 충돌
  - claim 이 publication 축만 검사 — **resolve 선점** 순서에서 `REQUESTED` + terminal 조합이 생긴다
  - `record_kind=REPLAY` 를 "attempt 기록 존재" 로 치환한 것이 **동치가 아니다**
  - outbox cleanup 이 replay 행을 지워 root 가 `REQUESTED` 에 **영구 고착**
  - V-1 partition 변이가 **검출되지 않는다**(같은 key → 같은 파티션)
- 확인된 것: 2b-3 선배포 순서는 성립 · P5/P15 는 root 잠금으로 직렬화되어 무한 전이 없음
- raw: `.cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-1788278544-r2.json`

## 2026-09-02 — 계획 리뷰 라운드 3

- 항목: **10건** (P0:0, P1:7, P2:3) — 이 중 **6건이 2R 수정이 만든 새 결함**
- 처리: **반영 10건 / 기각 0건**
- 뒤집힌 전제:
  - claim 을 root 에 걸면 **두 번째 replay 가 구조적으로 불가능** — 첫 성공이 root 를 `PUBLISHED` 로 만들고 claim 은 `NULL`/`PUBLISH_FAILED` 만 받는다. V-16 의 "3회 replay" 가 성립하지 않았다
  - outbox **부재를 `PUBLISH_FAILED` 로 강등**하면 이미 발행된 사건을 실패로 감사 기록한다 (2R 이 넣은 fallback 을 3R 이 철회)
  - `replay_deadline` 의 **durable writer 가 없었다** — transient 계산으로도 검증이 green
  - V-2 는 상속 때문에 변이를 검출하지 못하고, V-6 은 소비 멱등이 깨지지 않아 관측이 성립하지 않는다
  - kill-switch 가 "즉시" 차단된다 — Spring 정적 설정은 재기동이 필요하다
- 구조 변화: **target row ↔ 상관 앵커(root) 분리** · 10토픽 초기 정책표를 계획서에 확정 · `scripts/replay-drain-preflight.sh` 신설 · ADR-0020 Update Log 기재 항목 추가
- raw: `.cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-1788279282-r3.json`

---

## 수렴 판정

**3라운드 종료 시점에서 수렴하지 않았다.** 종료 조건은 *직전 라운드가 새 계약 표면을 추가하지 않았고 P1 = 0*
인데, 3R 은 P1 7건이었고 그 반영이 새 계약 표면(target row 모델 · deadline 영속 계약 · 초기 정책표 ·
preflight 스크립트 · ADR Update Log)을 다시 만들었다.

`/plan` 의 라운드 상한은 3회다. **4라운드 진행 여부는 사용자 확인 사항**이며, 건수 추세("16 → 14 → 10 이니 됐다")로
종료를 판정하지 않는다 — 3라운드 모두 **직전 라운드 수정이 만든 새 결함**을 실제로 잡아냈다.

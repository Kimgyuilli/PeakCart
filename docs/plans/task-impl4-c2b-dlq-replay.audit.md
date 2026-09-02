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

`/plan` 의 라운드 상한은 3회다. 건수 추세("16 → 14 → 10 이니 됐다")로 종료를 판정하지 않는다 —
3라운드 모두 **직전 라운드 수정이 만든 새 결함**을 실제로 잡아냈다.

**종료 결정 (2026-09-02, 사용자)**: 상한에서 종료하고 구현에 착수한다. **수렴해서 끝난 것이 아니라
상한에서 끊은 것**이며, 3R 반영이 만든 새 계약 표면 — target row ↔ 상관 앵커 분리(P21) ·
`replay_deadline` 영속 계약(P21 2b) · 10토픽 초기 정책표(P19) · `replay-drain-preflight.sh`(P24) —
은 **계획 리뷰를 거치지 않았다**. 이 표면들은 각 PR 의 **diff 리뷰가 첫 검토 지점**이 되므로,
해당 PR 본문과 진행 기록에 그 사실을 명시한다.

---

## 2026-09-02 — diff 리뷰 (PR ④-c-2b-1, P1~P7)

### 라운드 1 — 5건 (P0:0, P1:2, P2:3)
- 처리: **반영 5건 / 기각 0건**
- **리뷰가 내 코드의 실제 버그 2건을 잡았다.** 둘 다 뿌리가 같다 — **JPA 1차 캐시가 잠금 후 재검사를 무력화**한다:
  - `findPurgeable` 이 root 를 엔티티로 먼저 읽어 영속성 컨텍스트에 적재 → 뒤의 `SELECT ... FOR UPDATE` 는
    잠금만 얻고 **상태를 refresh 하지 않는다**. 재개방이 끼어들면 purge 가 캐시의 과거 terminal 상태로
    통과해 **살아 있는 incident 를 삭제**한다 → `findPurgeableRootIds`(id projection)
  - `transition` 도 같은 구조 → `findRootIdOf`(id projection). 두 요청이 OPEN 을 읽고 A 가 `RESOLVED` 를
    커밋하면 B 가 캐시의 OPEN 을 보고 **`DISCARDED` 로 덮어쓴다**
- 그 외: backlog 의 `publication` 이 `NULL`(요청 없음)을 누락 · replay-axis parity 가 정규화 해시라 주석 drift 통과 ·
  self-test 4 가 self-test 3 의 변조를 이어받아 **001 을 지워도 006 때문에 통과**하는 false-green
- 변이 **M6**(요청 행 엔티티 선읽기로 회귀) red 확인
- raw: `.cache/codex-reviews/diff-task-impl4-c2b-1-r1-1788341444.json`

### 라운드 2 — 5건 (P0:0, P1:2, P2:3) — **P1 2건 전부 1R 수정이 만든 새 결함**
- 처리: **반영 5건 / 기각 0건**
- 1R 수정이 만든 새 결함:
  - id projection 이 root 의 캐시 문제는 없앴지만 **비잠금 조회가 REPEATABLE READ 스냅샷을 먼저 연다**.
    root 는 current read 인데 자식은 스냅샷을 봐서, 앞선 트랜잭션이 root+자식을 종결한 뒤
    **root 에선 no-op 하면서 자식만 덮어쓰는** 상태가 된다 → `findChildrenForUpdate`
  - 신설 경합 테스트가 `Thread.sleep(500)` 으로 잠금 대기를 **추정** → 느린 CI 에서 결함 변이를 확률적으로 놓친다
    → InnoDB `LOCK WAIT` 실관측
- 그 외: backlog 5회 집계가 각각 별도 트랜잭션(합 불변식 미보장) · `CASE WHEN ... IS NULL` 의 NULL 분기 미검증 ·
  "원문 바이트 해시" 가 실제로는 text mode(개행 drift 통과)
- 변이 **M7**(자식 잠금 제거) · **M8**(CASE WHEN 제거) red 확인
- raw: `.cache/codex-reviews/diff-task-impl4-c2b-1-r2-1788343763.json`

### 라운드 3 — 2건 (P0:0, **P1:0**, P2:2)
- 처리: **반영 2건 / 기각 0건**
- `findChildrenForUpdate` 가 terminal 자식까지 잠가 대기 집합만 키움 → `status IN ('OPEN','ACKED')` 필터 ·
  `awaitLockWait()` 가 **컨테이너 전체 건수**만 봐 무관한 트랜잭션에도 latch 가 풀림 → 커넥션 id 로 대상 특정
- 리뷰가 확인해 준 것: P5·P15·purge 가 전부 root→자식 순서라 **잠금 순환 없음** · actuator readOnly 트랜잭션 ·
  Testcontainers 전용 root 접속 · 바이트 parity 해시에 결함 없음
- 변이 **M7b**(활성 필터 유지한 채 잠금만 제거) red 재확인
- raw: `.cache/codex-reviews/diff-task-impl4-c2b-1-r3-1788346508.json`

### 수렴
**3라운드에서 P0/P1 = 0 이고 반영한 2건이 새 계약 표면을 만들지 않았다**(잠금 범위 축소 · 테스트 관측 정밀화).
`/work` 의 재리뷰 조건은 "P0/P1 을 실제로 수정했을 때"이고 3R 은 P2 만 고쳤으므로 여기서 종료한다.

### 변이 검증 종합 (8종 전부 red → 복원 후 green)
M1 집계 root 조건 제거 · M2 `IS NULL` 분기 제거 · M3 purge 를 `COALESCE` 로 회귀 · M4 자식 id 의 root 정규화 제거 ·
M5 purge 가 자식을 남김 · M6 요청 행 엔티티 선읽기 · M7/M7b 자식 잠금 제거 · M8 `CASE WHEN` 제거.

> **M3 은 처음에 green 이었다** — purge 의 `COALESCE` 회귀 테스트가 잠금 후 인메모리 재검사에 가려 vacuous 했다.
> 쿼리 계약을 직접 단언해 red 로 만들었다. 계획 §1 **N17**(자기대조 금지)이 겨냥한 유형이 실제로 나왔고,
> **변이 검증이 없었으면 그대로 통과했다.**

---

## 2026-09-02 — /ship (PR ④-c-2b-1)

- PR: **[#100](https://github.com/Kimgyuilli/PeakCart/pull/100)** (머지 안 함)
- consistency precheck: **ok** (warnings 0) — 게이트 미노출
- 커밋 6개 (분류별 분리, mixed 0): `feat(deadletter)` · `test(deadletter)` · `chore(lint)` · `docs(impl4-c2b)` ×3
- 검증: **918 tests 0 failed** · parity lint 본체 + self-test 10종 · 변이 8종 red
- 갱신: `docs/TASKS.md`(④-c-2b 를 🔲 → 🔄 4분할, 2b-1 ✅ #100) · `docs/progress/PHASE4.md`(작업 이력 + 미충족 5건)
- Skipped findings: **없음** (계획 40건 · diff 12건 전량 반영, 기각 0)

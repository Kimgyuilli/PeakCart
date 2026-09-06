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

## 2026-09-02 22:26 — 계획 리뷰 (④-c-2b-2) · **리뷰 미실행**

- **착수 전 코드 검증은 완료** — C-1~C-13 을 계획서 §5 "PR ④-c-2b-2 / 착수 전 코드 검증" 표에 기록.
  뒤집힌 전제 5건(C-3 범위 2→4곳 · **C-5 outbox parity lint 부재** · **C-6 DELETE+LIMIT alias 불가** ·
  C-8 `cleanup.cron` 미선언 · **C-12 ADR-0012 D1 표 미갱신**) → P9-b·P9-c 신설, P9·P12 정정, V-30~V-33·R8 추가.
- **Codex 리뷰 3회 시도 전부 실패**:
  1. `plan-task-impl4-c2b-dlq-replay-2b2-r1-1788355609` — 10분 조사 후 **호출측 타임아웃으로 중단**(결과 없음). exec 다수 관측.
  2. `plan-2b2-r1b-1788356291` — exec 6회(계획서·ADR 만 읽음) 후 `items: []`. summary 는 *"…대조하겠습니다"* 라는 **예고문**.
  3. `plan-2b2-r1c-1788356371` — **exec 0회**, 즉시 `items: []` + 동일한 예고문.
  → 실패 양상: `--output-schema` 응답을 **조사 착수 전에 확정**해 버린다. codex-cli 0.152.1.
- **처리**: 반영 0건 / 기각 0건 (리뷰 산출물 없음). **P0/P1 = 0 이 아니라 "미측정"이다** — 자동 통과로 간주하지 않는다.
- raw: `.cache/codex-reviews/plan-2b2-r1{,b,c}-*.json` (+ `.stderr`)

## 2026-09-02 22:52 — 계획 리뷰 (④-c-2b-2) 라운드 1 · **분할 재시도**

긴 단일 프롬프트가 조기 종료를 유발한다고 보고 A(코드 사실 반증) / B(누락·부작용)로 나눠 호출.

- **A 성공** (exec 30회) — 2건(P0:0, P1:0, **P2:2**), **전량 반영**:
  - A#1 → C-3 의 stale 주석 범위가 **4곳 → 6곳**. `notification-service/build.gradle:27-28`·`:31-32` 추가.
    그리고 `NotificationApplication:12` 는 **P9 를 기다릴 것 없이 이미 거짓**이다(`DeadLetterMaintenanceScheduler:58·114`
    가 이미 `@Scheduled` 2개를 얹었다) — 선재 결함으로 재분류하고 같은 PR 에서 고친다.
  - A#2 → **내 C-6 정정이 틀렸다**. "단일 테이블 DELETE 에 alias 를 못 붙인다" 는 거짓이고,
    MySQL 8.0.16+ 는 `DELETE FROM tbl [[AS] alias] … LIMIT` 을 지원한다.
    **`mysql:8.0.46` 컨테이너로 직접 실측**해 반증을 확인하고 매트릭스 4행을 계획서에 기록:
    `AS o`+LIMIT **성공** / 초안(alias 미선언 `o.id`) **ERROR 1054** / 정규명 **성공** / multi-table+LIMIT **ERROR 1064**.
    → 초안 SQL 이 깨진다는 **결론은 유지**되나 이유가 다르다(alias 를 선언한 적이 없을 뿐).
    정규명을 택하는 근거를 "문법 제약" → "4서비스 byte 동일 복제의 diff 최소화" 로 정정.
- **B 실패 2회** (`plan-2b2-B-*`: exec 36회 후 `items: []` · `plan-2b2-B2-*`: exec 35회 후 `items: []`).
  둘 다 summary 가 *"…대조 중입니다"* 라는 진행형 예고문 — A 와 동일 조건에서 B 만 재현되는 조기 종료.
  → **3회째 동일 재시도 대신 스윕을 직접 수행**하고 결과를 C-14 로 기록했다. 결론: **갱신 필요 표면 0건**
  (ci.yml·k8s·notification 프로파일 yml·promql lint·ShedLock 락 이름·runbook 전부 무영향, 근거는 C-14 에 인용).
  B 축의 나머지(V-30~V-33 의 false-green 저항성)는 **제3자 미검토로 남는다** — 미충족에 명시.
- 처리: 반영 2건 / 기각 0건. raw: `.cache/codex-reviews/plan-2b2-{A,B,B2}-*.json`

## 2026-09-03 00:20 — diff 리뷰 (④-c-2b-2) · **Codex 미실행 (usage limit)**

- **시도 3회 전부 산출물 없음**. 1·2회는 `items: []` + *"…대조하겠습니다"* 예고문, 3회에서 원인이 드러났다:
  `ERROR: You've hit your usage limit ... try again at 3:00 AM` (codex-cli 0.152.1).
  → **앞선 두 번의 빈 응답도 프롬프트 문제가 아니라 같은 원인**이었을 가능성이 높다. 계획 리뷰 A/B 단계의
  빈 응답도 같은 신호였던 것으로 재해석된다(A 만 성공한 것은 그 시점에 잔여 quota 가 있었기 때문).
- **처리**: 반영 0건 / 기각 0건. **P0/P1 = 0 이 아니라 미측정**이다.
- **대신 수행한 것 (제3자 리뷰의 대체가 아님을 명시)**:
  · 자체 리뷰에서 **실제 결함 1건 발견·수정** — `DeadLetterPublicationReconciler` 를 4서비스 byte 동일로
    복제해 놓고 `dead-letter-schema-parity-lint` 의 `java_files` 목록에 더하지 않았다. 목록에 없으면
    4벌이 갈라져도 아무 것도 실패하지 않는다 — ④-c-2b-1 이 glob 을 안 넓혀 겪은 것과 **같은 구멍**을
    신규 파일에서 재현한 것이다. 목록 추가 + self-test 9b 신설 + 변이(M-6) red 로 고정.
  · **변이 검사 12종 전부 red** (아래 검증 절 참조)
- raw: `.cache/codex-reviews/diff-2b2-r1{,b,c}-*.json` (+ `.stderr`)

### 검증 (④-c-2b-2)

**모듈별 테스트** — 전 모듈 0 실패. 로컬 전체 스위트가 한 번에 완주하지 못해(장시간 백그라운드 잡이
반복 중단) **모듈 단위로 나눠 실행**했고, 각 모듈의 결과는 변경 이후 시점의 것이다:

| 모듈 | tests | 실패 |
|---|---|---|
| common | 73 | 0 |
| order-service | 317 | 0 (신규 7) |
| product-service | 186 | 0 |
| payment-service | 168 | 0 |
| notification-service | 45 | 0 (신규 5) |
| peekcart-common-auth | 52 | 0 |
| user-service · gateway | 61 · 80 | 0 |

**변이 검사 12종 전부 red** (복원 시 green):

| # | 변이 | red 가 된 검증 |
|---|---|---|
| M-A | `isReplay()` 를 `!= DOMAIN` 으로 (NULL 이 replay 가 됨) | V-30 |
| M-B | reconciler 가 `PENDING` 도 종착 | V-33 |
| M-C | outbox 부재를 `PUBLISH_FAILED` 로 강등 | V-21d |
| M-D | cleanup 의 `NOT EXISTS` 제외 조건 제거 | V-21b |
| M-E | replay 경로가 `source_record_timestamp` 미탑재 | replay 좌표 |
| M-F | notification poller 의 실제 발행 제거 | **V-32 가 "배선됐다" 판정이 아님을 실증** |
| M-1~M-5 | lint 의 OUTBOX-PARITY-002/004/005/007/009 검사 각각 제거 | self-test 10~15 |
| M-6 | `java_files` 목록에서 reconciler 제거 | self-test 9b |

> **M-4 는 1차 시도에서 GREEN 으로 보고됐으나 실제로는 변이가 적용되지 않은 것**(내 변이 스크립트의
> anchor 불일치)이었다. 앵커를 고쳐 재실행하니 red. **변이 하네스 자체의 false-green** 이므로 기록한다.

**lint 7종 green**: `dead-letter-schema-parity`(self-test **17종**) · `kafka-subscription-contract` ·
`observability-promql` · `observability-ssot` · `ci-test-matrix` · `saga-contract-matrix` · `e2e-network-contract`.

**실행 중 정정 3건**:
1. `NotificationCleanupMatrixIntegrationTest` 가 "notification 은 outbox cleanup 부재" 를 단언하고 있었다 —
   ADR-0020 D2 가 바꾼 계약이라 갱신하고, "테이블만 있고 아무도 발행하지 않는" 상태가 통과하지 않도록
   poller·reconciler bean 검사를 더했다. **계획 §8 이 이 파일을 예상하지 못했다.**
2. at-least-once 단언이 "정확히 2개" 였는데 실측 3개 — 배경 poller 도 같은 행을 집어간다. ADR-0020 D1 은
   중복 수에 상한을 두지 않으므로 **계약이 말하지 않는 것을 테스트가 주장**하던 것이고 flaky 였다.
   `≥2 + 전부 동일 payload + offset 중복 없음` 으로 정정.
3. cleanup 2회 호출 사이에 `lockAtLeastFor(PT1M)` 를 만료시키지 않아 두 번째가 통째로 건너뛰어졌다 —
   "제외 조건이 계속 막고 있다" 와 "잡이 안 돌았다" 가 구분되지 않는 상태였다.

**미충족**:
1. **Codex diff 리뷰 미실행** (usage limit, 3:00 AM 리셋) — P0/P1 = 0 이 아니라 **미측정**
2. **로컬 전체 스위트 1회 완주 없음** — 모듈별 그린을 합산한 것이다. CI 에서 확인 필요
3. 신규 테스트는 order·notification 에만. product/payment 는 byte 동일 복제 + parity lint 로 대체
4. replay 행은 **fixture 로만** 생성 — 진입점은 ④-c-2b-4
5. 운영 클러스터 미적용 · E2E(`saga_e2e.py`) 로컬 미실행

### 추가 — parity lint `DLQ-PARITY-014` (계획에 없던 확대)

self-test 9b(“reconciler drift 를 잡는가”)는 **내가 기억한 그 파일 하나만** 본다. 다음 신규 복제본에는
아무 도움이 안 되므로, 목록 자체를 검사가 지키도록 `DLQ-PARITY-014` 를 넣었다 —
*지금 4벌이 byte 동일한데 `java_files` 에 없는 파일*을 위반으로 본다.

- **디렉토리 전체 일치를 요구하지 않는다**: `DeadLetterConsumer`/`KafkaConfig`/`QuarantineConsumer` 는
  토픽·group 이 서비스마다 정당하게 다르다. 첫 구현이 이 구분을 놓쳐 self-test 1 이 red 였고, 그것이
  설계를 정정하게 했다.
- **부수 발견**: 기존 미등록 복제본 2개(`DeadLetterContainerGuard`·`DeadLetterKafkaConfig`, ④-c-2a 산출물)를
  찾아내 목록에 편입했다. **계획에 없던 소폭 확대**이며, allowlist 로 덮는 대신 편입한 이유는 그것들이
  실제 복제 자산이기 때문이다("의도적으로 무방비" 라는 거짓을 기록하지 않는다).
- **fixture 오염 2건도 함께 고쳤다**: ① `seed_fixture` 가 per-service 파일까지 order 사본으로 채워
  fixture 안에서만 byte 동일해지던 것 ② `seed_fixture` 가 `$TMP` 를 지우지 않아 9c 가 심은 파일이
  뒤 케이스를 red 로 만들던 것. **fixture 가 현실을 왜곡하면 self-test 가 검사하는 대상이 현실이 아니다.**
- 변이 **M-7**(014 검사 제거) red · **M-8**(byte 동일 조건 제거 → per-service 파일 오탐) red.
  양방향을 다 잡는다. self-test **18종** 통과.

## 2026-09-03 01:10 — `/ship` (④-c-2b-2)

- **PR**: https://github.com/Kimgyuilli/PeakCart/pull/102
- **precheck**: `ok` (warnings 0) — 자동 통과
- **커밋 6개** (한 커밋 = 한 분류): `feat(outbox)` · `feat(deadletter)` · `test(outbox)` · `chore(lint)` ·
  `fix(adr)` · `docs(impl4-c2b)`. ADR 과 계획서는 별도 커밋.
- **갱신**: `docs/TASKS.md`(④ 행에 #102 + 범위 변화·미충족 기록) · `docs/progress/PHASE4.md`(작업 이력 + 미충족 7항목)
- **편입 부채**: 없음
- **머지하지 않았다.** **diff 리뷰가 미측정 상태**로 남아 있으므로 머지 전 리뷰를 권한다.

## 2026-09-03 16:40 — CI 실패 대응 (④-c-2b-2, PR #102)

- **CI**: `test (order-service)` 만 실패 — `317 tests, 1 failed` (`OutboxAtLeastOnceIntegrationTest:105`).
  lint·guards·나머지 5 test job pass. `gate` 실패는 전파, e2e/images/publish 는 그로 인한 skip.
- **운영 코드 결함 0.** 전부 내 테스트 하네스 결함이며 4건이었다. 상세·반증된 가설 4개·내가 틀렸던 추론
  2건은 계획서 §5 “CI 후속” 에 기록.
- **핵심 증거**: 실패 시 `brokerEndOffsets` 전부 0 → 소비 실패가 아니라 **1사이클 send 실패**(토픽 준비 경합).
- **계획 C-9 결정을 뒤집었다** — `@Scheduled` 리터럴 유지 → `fixedDelayString` 설정화. 배경 잡이 도는 상태에서는
  발행 횟수를 세는 테스트가 구조적으로 성립하지 않는다. 운영 기본값 5s 불변, 4서비스 복제 유지(parity green).
- **검증**: 두 클래스 동시 6회 연속 green(느린 실행 포함) · notification 2종 green · parity self-test 18종 green.
- **남은 것**: CI 재확인. Codex diff 리뷰는 여전히 **미측정**(usage limit).

## 2026-09-04 — CI green 확인 + 미충족 갱신 (④-c-2b-2, PR #102)

- **CI 전면 green**: test 6종 · lint · guards · gate · images 6종 · **e2e**. (`publish` 는 main 전용 skip.)
- **해소된 미충족 2건**:
  · ~~로컬 전체 스위트 1회 완주 없음~~ → CI 전 모듈 pass
  · ~~E2E 로컬 미실행~~ → CI **e2e pass**. `EXPECTED_MIGRATIONS` 를 실제 스택에서 대조하므로
    4 DB 마이그레이션·notification outbox 신설의 **실적용**이 확인됐다
- **남은 미충족**: Codex diff 리뷰 **미측정**(usage limit — CI green 이 리뷰를 대신하지 않는다) ·
  신규 테스트는 order·notification 만 · replay 행은 fixture 로만 · `NOT NULL` contract(R1) ·
  `05:177`(P24) · 운영 클러스터 미적용 · gateway 로컬 미재실행
- 반영처: PR #102 본문(`gh pr edit`) · `docs/TASKS.md` ④ 행 · `docs/progress/PHASE4.md` · 본 audit

## 2026-09-05 — 계획 리뷰 라운드 1 (④-c-2b-3 구간)
- 항목: 17건 (P0:0, P1:12, P2:5)
- 처리: 반영 17건 / 기각 0건
- 뒤집힌 전제: 착수 전 코드 검증(C-15~C-28)이 초안 전제 3건을 먼저 뒤집었고(pc-replay-* 상수 부재 · DlqHeaders allowlist 자료구조 부재 · replay 앵커 컬럼 미매핑), 리뷰가 **그 검증 표 자체를 4건 반증**했다:
  - C-18 "마이그레이션 0" → payload digest 컬럼 신설로 철회 (4서비스 additive)
  - C-24 "호출부 2곳" → 실측 44곳 · notification 에 quarantine consumer 없음 → 시그니처 확대 폐기, LedgerOwner 빈 주입
  - C-27 "누락은 자동으로 막힌다" → DLQ-PARITY-014 는 4벌이 이미 동일할 때만 신고 (과장)
  - P15 "기존 best-effort 계약" → 현재 record() 는 @Transactional 안에서 Slack 호출 (javadoc 이 이미 거짓)
- 구현했으면 false-green 이었을 것: insertIfAbsent 의 clearAutomatically 가 잠근 root 를 detach → 재개방 UPDATE 미발생 (#2) · attempt-id TOCTOU (#3)
- ADR 충돌 확인: ADR-0020 §D5-4 가 record_kind=REPLAY 대조를 요구하면서 같은 절에서 outbox 를 정본으로 쓸 수 없다고 적는다 → P14(f) 가 이 PR 에서 개정
- raw: .cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-2b3-r1b.json

## 2026-09-05 — 계획 리뷰 라운드 2 (④-c-2b-3 구간)
- 항목: 9건 (P0:0, P1:6, P2:3) — 전부 1R 수정이 만든 새 표면을 겨냥
- 처리: 반영 9건 / 기각 0건
- 1R 수정이 만든 새 결함 6건:
  - digest 컬럼 신설하고 writer 미배정 → 실제 replay 에서 root digest 영구 NULL, 대조 축 9가 늘 통과 (fixture 때문에 green)
  - digest 마이그레이션 번호가 P23 backfill 과 충돌 (V10/V8/V8/V6 중복) → P23 을 V11/V9/V9/V7 로
  - 송신 allowlist 를 "부분집합" 으로 규정 → 헤더 0~3개짜리 REPLAY 통과, 발행 측에서 N11 파손
  - ADR 개정을 Update Log 로 하려 함 → adr/README.md:14 가 명시적으로 금지 (트레이드오프 변경은 새 ADR) → ADR-0021 신설 + ADR-0020 Partially Superseded
  - 롤백 drain 을 REQUESTED==0 으로 판정 → ack 시 PUBLISHED 로 바뀌므로 재시도 중/DLT 이동 중 레코드를 못 잡음 → 4조건으로 확대
  - "lint 본실행이 digest parity 를 강제" → 거짓. replay 축 검사는 glob 1파일만 보고 컬럼 하드코딩 → 최종 스키마 합성 기준으로 전환 (같은 구멍 3번째 재발)
- 남은 P2 3건: target-group 헤더 죽은 데이터(3자 대조) · V-19 축 중복(V-19a~m ID 배정) · Counter 트랜잭션 의미 미정의(CommitAwareMetrics + bounded reason)
- 반증되지 않은 것: P14(a)(c) 키 정본·판독 · P15(a) 실행 순서 재정의 · P15(f) LedgerOwner 빈 주입
- raw: .cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-2b3-r2.json

## 2026-09-05 — 계획 리뷰 라운드 3 (④-c-2b-3 구간) — **미실행 (usage limit)**
- 항목: 0건 — **측정하지 못했다** (P1=0 이 아니라 미측정)
- 원인: Codex `usage limit` (재개 가능 시각 2026-09-07 14:45). 탐색은 끝냈으나 최종 JSON 미출력.
- 수렴 판정: **미달**. 2R 이 P1 6건이었고, 그 수정이 새 계약 표면을 또 만들었다
  (ADR-0021 신설 · parity lint 를 최종 스키마 합성 기준으로 전환 · 송신 4값 유효성 · Flyway 재배정 ·
   P21 digest writer + V-30 · group 3자 대조 · drain 4조건 preflight · V-19a~m · CommitAwareMetrics Counter).
  건수 추세로 종료 판정하지 않는다.
- raw: .cache/codex-reviews/plan-task-impl4-c2b-dlq-replay-2b3-r3.stderr (JSON 없음)

## 2026-09-06 — diff 리뷰 라운드 1 (④-c-2b-3a) — **미실행 (usage limit)**
- 항목: 0건 — **측정하지 못했다** (P0/P1=0 이 아니라 미측정)
- 원인: Codex `usage limit` (재개 2026-09-07 14:45). 계획 리뷰 1R/2R 이 한도를 소진했고 3R 부터 막혔다.
  **PR 분할이 이 문제를 해소하지 못했다** — 분할의 전제는 "각 조각이 한도 안에서 리뷰된다" 였는데,
  한도는 조각 크기가 아니라 이미 소진된 잔량에 걸렸다.
- 검증(리뷰 대체 불가, 별개로 수행): 1006 tests 0 실패(8모듈) · lint 15종 green ·
  parity self-test 23종 · 변이 1종(requireComplete 제거 → 거부 4종 red, 정확히 그 4종만)
- raw: .cache/codex-reviews/diff-c2b3a-r1.stderr (JSON 없음)

## 2026-09-06 — /ship (④-c-2b-3a)
- PR: https://github.com/Kimgyuilli/PeakCart/pull/103
- precheck: ok (warnings 0)
- 커밋 4개: 계획(검증표+2R 반영) · 계획(3a/3b 분할) · feat(P14 구현) · docs(검증 결과+리뷰 미실행)
- 갱신: TASKS.md ④ 행 · PHASE4.md 작업 이력 · 계획서 진행 상태(2b-2 를 ✅ 로 정정 — #102 머지 반영 누락분)
- 미충족(PR 본문 §미충족 6항): diff 리뷰 미측정 · 계획 3R 미측정 · digest writer 부재(2b-4) · 상관 로직 부재(2b-3b) · 운영 클러스터 미적용 · 로컬 e2e 미실행

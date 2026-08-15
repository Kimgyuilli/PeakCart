# task-impl4-c1-refund-path — 리뷰 audit

## 2026-08-15 01:35 — GP-2 (loop 1)

- 리뷰 run: `plan:20260814T162630Z:380c8fba-676e-4ec3-b9dd-605d63cc25b5:1` (exit 0)
- 항목: 17건 (P0:0, P1:13, P2:4) — **전량 반영**
- raw: `.cache/codex-reviews/plan-task-impl4-c1-refund-path-1786724790.json`

**중점 검증 결과**: §2.1 의 b·c·d·i·j 는 코드와 일치. **g 는 3서비스가 아니라 4서비스**(notification 포함), **h 는 부정확**(`failure_code` 가 현재 없음) — 둘 다 정정.

| # | sev | 지적 | 처분 |
|---|---|---|---|
| 1 | P1 | dispatcher 트랜잭션 경계 미고정 — 하나의 `@Transactional` 로 만들면 PG 호출 전 사망 시 `CLAIMED` 가 롤백돼 **crash matrix 의 관측 상태와 달라진다** | **반영**. P4 를 3단계로 고정(T1 claim 커밋 → 트랜잭션 밖 PG 호출 → T2 결과 확정) + self-invocation 회피(별도 bean/`TransactionTemplate`) 명시 |
| 2 | P1 | stale `CLAIMED` 복구의 핵심인 **"미취소면 동일 멱등키로 재호출"** 이 P6 에서 누락. claim lease 임계값도 없음 | **반영**. 조회 결과 4분기 확정 + lease 임계값 정책화 + Clock 기반 경계 테스트 |
| 3 | P1 | D5 의 PG 재시도 3회·백오프가 구현/검증 항목에 없음. 재시도마다 동일 멱등키 전송 검증도 없음 | **반영**. P5 에 재시도 주체·횟수·백오프·재시도 가능 예외 집합 + `MockRestServiceServer` 헤더 동일성 검증 + 소진 시 `UNRESOLVED`/`attempts=3` |
| 4 | P1 | 결과별 회신이 불완전 — `FAILED` 회신, reconciliation 확정 시 회신이 빠짐 | **반영**. P4-3·P6 양쪽에 "원장 전이 + Payment 전이 + 회신 Outbox = 한 트랜잭션", `UNRESOLVED` 는 **회신 없음**을 명시 |
| 5 | P1 | 수동 종결 감사 계약 불완전 — `resolved_by` 만 있고 **사유 컬럼 없음**, 호출 경로·권한·중복 종결 미정의 | **반영**. `resolution_reason` 컬럼 + actor + 허용 전이(`UNRESOLVED→FAILED`) + 중복 no-op + 운영 전용 application service(외부 API 미노출) |
| 6 | **P1** | **④-c-1a 가 독립 배포 단위가 아니다** — poller 가 `eventType` 을 그대로 토픽으로 발행하는데 `payment.refunded` NewTopic·DTO 가 1b 소관이라 1a 의 발행이 성립하지 않는다 | **반영**. `PaymentRefundedPayload` + `payment.refunded`(+dlq) NewTopic 을 **1a 로 이동**(P8) + `auto-offset-reset=earliest` 로 1b 지연 소비 허용 + **rollout gate**(같은 릴리스 주기, retention 초과 금지) |
| 7 | P1 | PR 별 완료 조건 귀속 없음 — R-2 는 1b 에서만 닫히는데 1a 를 "end-to-end" 라 서술. P14(문서)가 어느 PR 에도 미귀속 | **반영**. §6 을 1a/1b/공통으로 분리하고 **"1a 완료 보고에 R-2 미해소를 명시"** 를 조건으로 못박음. 문서 항목을 1b 에 귀속 |
| 8 | P1 | fence 중복을 "정상 no-op" 으로 만드는 **DB 메커니즘이 없음** — JPA `save` 의 유니크 위반은 flush/commit 시점에 터져 catch 를 우회하고 rollback-only 를 만든다 | **반영**. P3 를 **단일 원자 쿼리**(`INSERT ... ON DUPLICATE KEY UPDATE` / `INSERT IGNORE`) + 영향 행 1/0 분기로 고정, JPA save+catch **금지** 명시(§2.1-m 근거 추가) |
| 9 | P1 | §5 의 동시성·crash·동일 트랜잭션 검증이 **false-green 가능**(④-b 전례) | **반영**. P14 를 **Testcontainers + Spring 프록시** 기준으로 재작성, 장애 주입 지점을 칸별로 지정(claim 커밋 직후 / PG 성공 후 finalize 실패 / 3회 타임아웃), DB 재조회 판정 |
| 10 | P1 | 회신 종결 마이그레이션 불완전 — Order `failure_code` 부재, Product 종결 컬럼 전무, §4/§5 의 "마이그레이션 4종" 이 실제 파일과 불일치 | **반영**. Order V5(`failure_code`+엔티티)·Product V4(컬럼명/길이/null 규칙) 명시, **마이그레이션 3파일**로 정정, 컬럼 추가와 backfill DML 을 같은 버전에 묶음 |
| 11 | P1 | **`payments.user_id` 가 nullable** 인데 ADR 은 `payment.refunded.userId` 를 필수로 결정 | **반영**. §2.1-l 신설 + P2 처분(널 0이면 NOT NULL contract / 아니면 **fence 진입 차단** + 운영 알림 + `UNRESOLVED`), §6 완료 조건에 검증 편입 |
| 12 | P2 | 신규 Outbox 의 `aggregateType`·backfill `NOT EXISTS` 일치 조건 미정의 | **반영**. §2.1-o 표 + P11 에 `(aggregate_type, aggregate_id, event_type)` 기준 명시 + 2회 실행 0건 검증 + 복합 인덱스 |
| 13 | P1 | D1 의 **cross-topic 순서 무보장**에 대응하는 검증 없음 | **반영**. P14 에 3진입점 **전 순열·중복 조합** 결정적 테스트 + 미준비 순서의 pending marker/재평가 규칙 요구 |
| 14 | P1 | ADR 이 ④-d 에 요구한 환불 체인 E2E·saga-contract 게이트가 계획에 연결 안 됨 | **반영**. P13 범위에 **부모 §3 P12(E2E)·P14(게이트)** 갱신 추가 — 실행은 ④-d, 요구사항은 지금 등재 |
| 15 | P2 | ADR-0007 설정 소유 미확정(주기·lease·배치·retry·상한) | **반영**. **P9 신설** — `RefundProperties` `@ConfigurationProperties` + base yml 단독 소유 + 상호관계 `@AssertTrue` fail-fast + 부팅 테스트 |
| 16 | P2 | §2.1-g "3서비스 공통" 이 실제와 불일치(notification 포함 4서비스) | **반영**. 4서비스로 정정 + 신규 listener 5개의 factory 사용 검증을 P14 에 추가 |
| 17 | P2 | §2.2 의 `PaymentStatus` 영향 점검 참조가 P11(크로스서비스 소비)로 잘못 연결. 실제로 즉시 깨지는 건 `PaymentStatusTest` | **반영**. 참조를 P2 로 교정 + 전수 전이표·`cancelBeforePayment` no-op·직렬화 회귀를 P2 에 열거(§2.1-n 근거 추가) |

### 검증 메모 (GP-2)

**#6 이 분할 자체를 깼다.** "1a 는 payment-service 단독으로 닫힌다"고 적었지만, outbox poller 가 `eventType` 을 그대로 토픽명으로 쓰기 때문에 회신 토픽이 없으면 **발행 자체가 성립하지 않는다**. 서비스 경계로 자른 분할이 인프라(토픽 선생성) 때문에 안 맞은 사례 — 분할 기준을 "모듈 소속"이 아니라 **"런타임에 필요한 것이 다 있는가"** 로 봐야 했다.

**#8 은 ④-b 의 false-green 과 같은 계열**이다. "유니크 위반을 catch 해서 no-op" 은 JPA 에서 **의도대로 동작하지 않는다**(flush 시점·rollback-only). 계획서에 "결과"만 적고 **메커니즘을 안 적으면** 구현이 되는 방식으로 흘러간다.

**#1·#2·#4 는 전부 "ADR 은 정했는데 계획이 안 옮긴 것"** 이다. ADR 이 상세할수록 이행 계획이 ADR 을 요약하면서 결정을 흘리기 쉽다 — 다음 ADR 이행 계획에서는 **D-항목 ↔ P-항목 대응표**를 계획서에 명시적으로 두는 게 낫다.

---

## 2026-08-15 03:20 — GW-2 (loop 1, split c1..c3) — ④-c-1a 구현

- 리뷰 run: `work:...:1:c1` / `:2:c2` / `:3:c3` (split 3, 각 ~750-870줄, 전부 exit 0)
- 항목: 23건(중복 포함) → **중복 제거 10건** (P1 7 · P2 3) — **전량 반영**
- diff: `.cache/diffs/diff-task-impl4-c1-refund-path-1786728812.patch` (2,377줄)

| # | sev | 지적 | 처분 |
|---|---|---|---|
| A | P1 | `ALREADY_CANCELED` 를 조회 없이 SUCCEEDED 확정 — 외부 부분취소도 `REFUNDED`+성공 회신 | **반영**. `RefundExecutor.verifyByQuery` 로 조회 3분기 |
| B | P1 | **stale CLAIMED 를 dispatcher 가 조회 없이 재호출** — ADR crash matrix a/b 경로가 코드에 없음 | **반영**. claim 을 `claimRequested`(REQUESTED 전용)/`claimForReconcile`(stale+UNRESOLVED)로 분리, reconciliation 이 `verifyThenExecute`(조회 선행) |
| C | P1 | `pg-timeout` 이 RestClient 에 미적용 — lease fail-fast 의 전제가 없음 | **반영**. `TossClientConfig` 의 `RestClientCustomizer`(클라이언트가 직접 factory 를 세팅하면 MockRestServiceServer 바인딩을 덮어써 계약 테스트 불가) |
| D | P1 | `payments.user_id` nullable — 회신 필수 필드가 null 가능 | **반영**. V4 가드 후 NOT NULL 전환 + 서비스 진입 차단(PAY-011) — 스키마·코드 이중 방어 |
| E | P1 | finalize 경합 — 만료 owner 와 새 owner 가 둘 다 확정 | **반영**. `generation` fencing token + claim 시 증가 |
| F | P1 | `canceledAmount >= amount` 로 초과 취소도 성공 | **반영**. `==` 로 축소, 부분·초과 모두 `AMOUNT_MISMATCH` |
| J | P1 | UNRESOLVED starvation — 조회 실패 배치가 영구 점유 | **반영**. `claimed_at` 순회 + per-row claim 이 갱신 → 자연 회전, `max-batches-per-run` 도입 |
| G | P2 | 수동 종결이 `CLAIMED` 도 닫고 상한 미검사 | **반영**. UNRESOLVED + 상한 초과만 허용 |
| H | P2 | crash(a) 테스트가 false-green — B 를 통과시킴 | **반영**. "stale CLAIMED 는 dispatcher 후보가 아니다"를 단언하도록 재작성 + `RefundExecutorTest` 신설(호출 순서 `inOrder` 검증) |
| I | P2 | 정책값이 `RefundProperties` 밖 | **반영**. intervals·lock·max-batches 편입 + `@AssertTrue` 2종 |

### 검증 메모 (GW-2)

**B·H 가 짝이었다** — 잘못 구현한 경로를 테스트가 정당화하고 있었다. "stale claim 이 재claim 된다"를 성공 기준으로 적었는데, ADR 은 **재claim 전에 조회**를 요구한다. 테스트가 계약이 아니라 구현을 기술하면 이런 식으로 굳는다.

**MySQL found-rows 시맨틱**(구현 중 실측): `ON DUPLICATE KEY UPDATE id=id` 는 중복 시 **1**을 반환한다(Connector/J 기본이 affected-rows 가 아님) → 두 진입점이 모두 fence 승자로 판정됐다. `INSERT IGNORE` 로 교체해 0/1 로 갈랐다.

---

## 2026-08-15 03:45 — GW-2 (loop 2, single, 예산 초과 승인) — 반영 검증

- 리뷰 run: `work:20260814T182215Z:...:4` (main 코드만 1,776줄, exit 0)
- 항목: **8건** (P1 5 · P2 3) — **전량 반영**
- raw: `.cache/codex-reviews/diff-c1a-fix-1786731735.json`

| # | sev | 지적 | 처분 |
|---|---|---|---|
| 1 | P1 | **generation 검사가 TOCTOU** — 잠금 없는 SELECT 후 비교라 그 사이 재claim 이 끼어들 수 있음 | **반영**. `findByOrderIdForUpdate`(PESSIMISTIC_WRITE)로 확정·수동종결 경로 잠금 |
| 2 | P1 | UNRESOLVED 를 claim 해도 상태가 UNRESOLVED → **진행 중인 건을 수동 종결 가능**(환불 성공 ↔ 원장 FAILED) | **반영**. `claimForReconcile` 이 `CLAIMED` 로 전이 + 상태머신에 `UNRESOLVED→CLAIMED` 추가 |
| 3 | P1 | NULL 가드가 **CREATE TABLE 뒤**라 실패 시 잔존 객체 때문에 재배포 불가 | **반영**. 가드를 모든 DDL 앞으로 이동(파일 재작성) |
| 4 | P1 | `payment_key` 200 vs payments 255 → INSERT IGNORE 가 **잘린 키를 조용히 삽입** | **반영**. 255 로 통일 |
| 5 | P1 | `worstCaseCall` 이 pgTimeout 을 시도당 1회만 계산 — 실제로는 connect+read 양쪽 | **반영**. 시도당 2배로 계산 |
| 6 | P2 | lock 검증이 배치 1회분만 — 스케줄러는 `maxBatchesPerRun` 반복 | **반영**. 실행 전체 기준으로 검증식 확대 + 기본값 조정(batch 5×2, lock 20m) |
| 7 | P2 | `recordAttempts` 가 대입이라 조회만 한 확정(0)이 이전 기록을 지움 | **반영**. 누적으로 변경 + 회귀 테스트 |
| 8 | P2 | code 없는 4xx 가 `failureCode=null` FAILED 회신 | **반영**. `HTTP_<status>` 대체값 + 엔티티에서 `UNKNOWN_PERMANENT_FAILURE` 방어 |

### 검증 메모 (loop 2)

**1·2 는 loop 1 수정이 만든 새 결함**이다 — generation 을 도입했지만 읽기를 잠그지 않았고, UNRESOLVED claim 이 상태를 안 바꿔 G(수동 종결 제약)를 우회했다. **재리뷰를 돌리지 않았으면 둘 다 남았을 것**이고, 특히 2 는 "환불은 성공했는데 원장은 실패"라는 최악의 불일치를 만든다.

**3 은 내 python 재배치 스크립트가 파일 구조를 깨뜨려** 마이그레이션이 문법 오류로 실패했고(통합테스트 전량 실패로 발견), 파일을 손으로 다시 썼다.

---

## 2026-08-15 — /done applied (PR https://github.com/Kimgyuilli/PeekCart/pull/87)

- **TASKS.md**: 구현 ④ 행에 ④-c-1a 완료 요약(구조 결정·실측 정정·리뷰 2라운드) + R-2 미해소·rollout gate 명시. ④-c-1b 대기
- **PHASE4.md**: 진입점/실행자 분리 근거 · 보장 문구 재정의 · found-rows 실측 · 리뷰 2라운드(특히 라운드1 수정이 만든 새 결함 2건) · 미충족 4건
- **ADR**: 신규 없음(ADR-0018 이행). Status 변경 없음
- **Layer 1**: 미갱신 — 1b 에서 크로스서비스 표면이 완성된 뒤(계획 P15)
- 커밋 6개, 브랜치 `feat/impl4-c1a-refund-engine`

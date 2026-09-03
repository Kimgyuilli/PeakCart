# ④-c-2b — DLQ replay 경로 (구현)

> 부모 계획: `docs/plans/task-impl4-choreography-saga.md` **P9 · P10 · P13(나머지)**
> 형제: **④-c-2a (원장 적재)** — `task-impl4-c2a-dlq-ledger.md` ([#90](https://github.com/Kimgyuilli/PeakCart/pull/90))
> 선행: **[ADR-0020](../adr/0020-dlq-replay-contract.md)** ([#98](https://github.com/Kimgyuilli/PeakCart/pull/98)) · **④-c-2b-0** 브로커 retention 실설정 ([#99](https://github.com/Kimgyuilli/PeakCart/pull/99))
> 상태: **계획 확정** (2026-09-02, 리뷰 1R 16건 전량 반영). 착수 조건 4개 전부 종료 — 부록 A.3 참조.
> 범위 분리 이력(D1~D8 이 왜 ADR 사안이었나)은 **부록 A** 에 보존한다.

---

## 1. 명제 — 무엇이 성립하면 **미완**인가

계약의 정본은 ADR-0020 이다. 이 계획은 그 계약이 **코드로 강제되는지**만 판정한다.
아래 중 **하나라도 성립하면 ④-c-2b 는 미완**이다.

| # | 미완 조건 | 대응 ADR |
|---|---|---|
| **N1** | 재발행이 원본과 다른 토픽·파티션·key·payload·`eventId`·timestamp 로 나갈 수 있다 | D3 · D8-3 |
| **N2** | 기록된 좌표를 읽지 않고, 혹은 **요청 offset ≠ 반환 offset** 인 채로 재발행할 수 있다 | D5-1 |
| **N3** | §D5-2 의 6 금지축(`event_id` NULL · `__unknown__` group · `DLQ_ORIGIN` · 좌표 무효 · 정책 금지 · `original_timestamp` NULL) 중 하나라도 통과해 발행에 도달한다 | D5-2 |
| **N4** | `replay_deadline` 이 재실패마다 재계산되어 안전창이 연장된다 | D5-3 |
| **N5** | `publication_status = PUBLISHED` 인 root 가 backlog·oldest-age 집계에서 빠진다 | D6-2 · D6-3 |
| **N6** | `publication_status = REQUESTED` 인 행을 `RESOLVED`/`DISCARDED` 로 닫을 수 있다 | D6-2b I-1 |
| **N7** | 종결된 root 에 늦은 자식이 도착했는데 root 가 재개방되지 않는다(또는 자식 적재가 거부·상속된다) | D6-2b I-2 |
| **N8** | 재실패 자식이 backlog 를 증가시킨다 — 재실패 N회에 backlog 가 1 을 넘는다 | D6-3 |
| **N9** | `root_record_id` 도입이 **기존 미결 행을 집계에서 탈락**시킨다 (배포 전후 미결 건수 불일치) | D6-3 전환 구간 |
| **N10** | 위조·타 서비스·다른 group·다른 root·다른 destination topic 의 상관 헤더가 **root 에 상관**된다 | D5-4 |
| **N11** | outbox `PUBLISHED` cleanup 이 먼저 돈 뒤 도착한 지연 DLQ 적재가 **독립 incident 로 갈라진다** | D5-4 수명 경쟁 |
| **N12** | 같은 원장 행에 동시 replay 요청이 와서 **outbox 행이 2개 이상** 생긴다 | D6-4 fence |
| **N13** | 종결이 broker ack 로 자동 수행된다 — 사람의 명시 전이 없이 `RESOLVED` 가 된다 | D6-2 |
| **N14** | replay 를 SQL 직접 변경이나 새 CLI 로 개시할 수 있다 (진입점이 `deadletter` 엔드포인트 1종이 아니다) | D7 |
| **N15** | notification 이 replay 를 지휘할 수 없다 — outbox 부재로 자기 원장 행의 재발행이 불가능하다 | D2 |
| **N16** | `record_kind` 를 명시하지 않는 신버전 writer 경로가 존재한다 (**코드 경로 강제** — DB `NOT NULL` contract 는 §10 R1 로 이연, 완료 조건 1 참조) | D3 expand→contract |
| **N17** | 검증이 **관측한 값을 그대로 기대값으로 적어** 통과한다 (자기대조) | ④-c-2b-0 diff 리뷰 1R 재발 방지 |

> **N17 을 명제에 올린 이유**: 직전 PR(#99)에서 `KafkaTopicConfigsTest` 가 SUT 상수를 기대값으로 읽어
> 8/4 → 10/2 MiB 변이에도 green 이었다. 같은 결의 실수가 ADR-0020 계획서(`task-adr0020-dlq-replay-contract.md` §3 **P4-4**)의 리뷰 단계에서도 나왔다 —
> **한 세션에서 두 번**. 이 계획의 모든 단언은 ADR 본문 값을 **리터럴로 독립 기재**하고 변이로 red 를 확인한다.

---

## 2. 배경 — 착수 전 코드 검증

**전제는 ADR 이 아니라 현재 코드다.** 아래는 계획 초안 작성 **전에** 직접 확인한 결과다.
(구조 변경 — 모듈 경계 이동·peel·rename — 은 **없다**. notification 의 `global/outbox` 는 기존 3서비스 코드의
**복제 추가**이고 옮겨지는 대상이 없으므로 `PLAN-BLINDSPOTS.md` B1 역의존 스윕 대상이 아니다.)

| # | 확인한 사실 | 근거 (직접 확인) | 계획에 미치는 영향 |
|---|---|---|---|
| V1 | outbox 8파일이 order/product/payment 에서 **byte 동일**하다. 단 `OutboxEventStatus` 만 다르다 — order/product 에는 `BACKFILL` 이 있고 payment 에는 없다 | `diff` 8종 실행 결과 | replay 컬럼·kind 분기를 **4벌**(notification 포함)에 동일 적용. `OutboxEventStatus` 는 손대지 않는다 |
| V2 | `buildRecord()` 는 `new ProducerRecord<>(eventType, null, aggregateId, payload)` — **토픽=eventType 고정 · partition=null · timestamp 미지정 · 헤더는 trace/user 2종** | `OutboxPollingService:119-125` | D3 분기 필요. 도메인 경로는 **불변**으로 유지 |
| V3 | 발행 crash window 는 실재한다 — `:83` ack, `:85-86` 별도 save | `OutboxPollingService:83-86` | D1 at-least-once. "중복 발행 0" 단언 금지 |
| V4 | `outbox_events.event_type` 은 **VARCHAR(50) NOT NULL**, `aggregate_id` **NOT NULL(50)**, `payload` **NOT NULL** | `V1__init_order.sql:69-81` · `OutboxEvent:22-35` | replay 행도 이 3개를 채워야 한다. **tombstone(payload NULL) replay 는 이 스키마로 표현 불가** → §10 미해결 R3 |
| V5 | 가장 긴 토픽명은 `stock.compensation.requested`(28자) | `DlqTopology:91` | `event_type`(50) 에 destination topic 을 넣어도 넘치지 않는다. 단 **정본은 `destination_topic` 컬럼**이고 `event_type` 은 중복 기재가 되므로 넣지 않는다(P10) |
| V6 | notification 에 outbox 가 **없다** — `global/` = `config·deadletter·idempotency` 뿐, 마이그레이션 V1~V3 | `find` · `ls db/migration` | D2 신설. Flyway 번호는 **V5**(V4 는 P1 이 쓴다) |
| V7 | notification 은 **ShedLock·validation·`KafkaTemplate<String,String>` 을 이미 갖췄다** | `build.gradle`(shedlock-spring/jdbc-template·starter-validation) · `ShedLockConfig` · `shedlock` 테이블(V2) · `NotificationKafkaConfig:34` | outbox 신설에 **새 의존성·새 인프라가 없다**. ADR 이 우려한 "인프라 증가" 는 poller·스케줄러 빈 3개 + 테이블 1개로 한정된다 |
| V8 | notification 은 `EnableScheduling` 이 이미 켜져 있고 `NewTopic` 을 **하나도 선언하지 않는다** | `NotificationApplication:14` · `grep NewTopic` | poller 가 즉시 돈다. replay 발행 대상 토픽은 전부 남의 프로비저닝 — **D8 예외의 가장 순수한 사례** |
| V9 | `DlqHeaders.parse()` 는 표준 `DLT_*` 만 읽고 **application 헤더를 일절 읽지 않는다**. `DlqOrigin` 에 헤더 필드가 없다 | `DlqHeaders:39-66` · `DlqOrigin:25-36` | D5-4 상관 헤더는 **판독 경로 신설**이 필요하다 (P14) |
| V10 | `DeadLetterRecorder.record()` 는 `insertIfAbsent` 1회 + 중복 시 `incrementAttempt` — **root 개념도 상관 대조도 없다** | `DeadLetterRecorder:52-87` | P3(self-root) · P15(원자 대조) 이 이 메서드를 재작성한다 |
| V11 | 집계 3종이 **JPQL 문자열 리터럴**로 상태를 박고 있다 — `status IN ('OPEN','ACKED')` | `DeadLetterRecordJpaRepository:78·82·86` | `RESOLVED` 추가만으로는 부족하고 **root 조건**까지 같은 3곳을 고쳐야 한다 |
| V12 | `findPurgeable` 은 `status = 'DISCARDED' AND discarded_at < :threshold` 만 본다 | `:94-96` | `RESOLVED` + `resolved_at` 를 **함께** 넣지 않으면 종결분이 영구 잔존한다 |
| V13 | `dlq.backlog`·`dlq.oldest.age` 게이지가 **같은 repository 메서드를 재사용**한다 | `DeadLetterMetrics:26-33` | 쿼리를 root-only 로 바꾸면 메트릭·actuator 가 **자동으로 함께** 바뀐다. 갈라질 위험이 없다 |
| V14 | Grafana alert 는 `sum by (application)(dlq_backlog{...})` 로 **메트릭 이름만** 참조한다 | `k8s/monitoring/shared/grafana-alerts.yml:292-303` | **alert 식 변경은 불필요**하다(ADR-0019 식 고정과 무충돌). 다만 *의미*가 행 → incident 로 바뀌므로 주석·runbook 갱신은 필요 |
| V15 | `status` 는 `VARCHAR(30)`, `attempt_count`/`occurred_at` 등 나머지는 NOT NULL | `V6__dead_letter_records.sql:43-50` | `RESOLVED` 추가에 마이그레이션 불필요(2a 확장점 유효). 신규 컬럼은 전부 nullable additive |
| V16 | Flyway 최신 번호: order **V7**(cursor index) · product V5 · payment V5 · notification V3 | `ls db/migration` | ADR Consequences 의 "order V6·product V5·payment V5·notification V3 계열" 서술은 **④-c-2a 가 쓴 번호**를 가리킨 것이고, 이번 신규는 **order V8 · product V6 · payment V6 · notification V4** 다 |
| V17 | `AdminClient` 를 **프로덕션 코드에서 쓰는 곳이 없다**. 테스트 1곳뿐 | `grep AdminClient` → `KafkaTopicConfigMechanismIntegrationTest` 만 | P18 좌표 reader 는 신규 표면이다. 그 테스트가 참조 구현이 된다 |
| V18 | `processed_events` 는 `(event_id, consumer_group)` UNIQUE + `processed_at` 로컬 시각 | `ProcessedEvent:16-33` | D1 "소비 효과 1회" 검증은 이 테이블의 **행 수**로 판정한다 |
| V19 | `findPendingEvents` 는 `status='PENDING' ORDER BY created_at ASC` — **kind 무관** | `OutboxEventJpaRepository:16` | replay 행도 같은 큐에 들어간다. 별도 poller 불필요(ADR Alternative D 기각과 정합) |
| V20 | `docs/05-data-design.md:177` 이 "소비 전용 notification 은 `processed_events` 만" 이라고 **명시**한다 | 해당 줄 | D2 로 거짓이 된다 → P24 문서 정정 대상 |

### 2.1 검증이 계획을 바꾼 곳

- **V16** — ADR 이 적은 마이그레이션 번호는 신규 번호가 아니었다. order 는 그 사이 `V7__orders_cursor_index.sql`(구현 ⑥ [#96](https://github.com/Kimgyuilli/PeakCart/pull/96))이 추가됐다. 번호를 그대로 썼다면 Flyway 충돌로 부팅이 깨졌다.
- **V7** — notification outbox 신설 비용이 ADR 추정보다 **작다**. ShedLock·validation·KafkaTemplate 이 이미 있어 새 의존성이 0이다. "소비 전용의 단순함을 잃는다"는 트레이드오프는 유효하나, 인프라 증분은 테이블 1 + 빈 3 이다.
- **V14** — ADR §D6-3 이 "경보" 를 갱신 대상에 넣었으나, 실제 alert 는 메트릭 이름만 참조하므로 **식 변경이 없다**. ADR-0019 의 식 정본 고정과 충돌하지 않는다. 갱신 대상은 문서(의미 변화)뿐이다.
- **V4** — `payload NOT NULL` 이라 **tombstone 레코드의 replay 는 현 스키마로 불가능**하다. ADR 은 이를 다루지 않았다. 범위 밖으로 두고 §10 R3 에 남긴다.
- **V5** — `event_type` 에 destination topic 을 넣으면 정본이 둘이 된다. `record_kind=REPLAY` 행의 `event_type` 은 **`__replay__` sentinel** 로 고정하고, 발행 토픽은 `destination_topic` 만 읽는다(P10).

---

## 3. ADR 선행 판단 — **불필요**

- 새 외부 의존성: 없다 (AdminClient 는 이미 클래스패스에 있는 kafka-clients 의 API)
- 아키텍처 경계 변경: 없다 (notification outbox 는 **ADR-0020 D2 가 이미 결정**)
- 새 환경/인프라: 없다 (k8s 매니페스트·GCP 리소스 무변경)

---

## 4. PR 분할

규모(P1~P25 · 4 DB · 5모듈)와 **ADR-0020 D3/D6-3 이 요구하는 expand → deploy → backfill 순서** 때문에
한 PR 로 묶을 수 없다. 배포 경계가 곧 PR 경계다.

| PR | 제목 | P 항목 | 배포 후 성립하는 것 |
|---|---|---|---|
| **④-c-2b-1** | 원장 축 확장 + incident 집계 정정 | P1~P7 | 두 축 분리·`RESOLVED`·root-only 집계·종결 전파 (**replay 는 아직 불가**) |
| **④-c-2b-2** | 발행 표면 — outbox replay kind + notification outbox | P8~P13 | replay 레코드를 **발행할 수 있는** poller (진입점 없음 → replay 행이 생기지 않는다) |
| **④-c-2b-3** | 재실패 상관 + 재개방 | P14~P17 | 재실패가 root 로 수렴하는 경로가 **먼저** 선다 (아직 replay 개시 불가) |
| **④-c-2b-4** | 좌표 reader + 진입점 + fence + backfill + 문서 | P18~P25 | replay **개시 가능** — 그 시점에 상관·재개방이 이미 배포돼 있다 |

> **순서 정정 (리뷰 1R #2)**: 초안은 진입점(2b-3)을 상관·재개방(2b-4)보다 **먼저** 열었다.
> 그러면 2b-3 만 배포된 구간에서 재발행분이 재실패할 때 **독립 incident 로 갈라져** ADR-0020 §D5-4·§D6-3 을
> 정면으로 위반한다. 대안으로 "endpoint 를 플래그로 잠갔다가 나중에 연다" 도 있었으나, 그것은
> **계약을 지키는 코드 대신 운영 규율에 기대는 것**이라 채택하지 않았다. 상관 경로를 먼저 세우면
> replay 가 열리는 순간 이미 계약이 성립한다.

**되돌리기**: 2b-1~2b-3 은 앞 PR 만 배포된 상태에서 정지해도 기존 동작이 유지된다 — 신규 컬럼은 전부
nullable 이고, `record_kind IS NULL → DOMAIN` 해석이 구버전 writer 를 흡수하며, 진입점이 없으면 replay 행
자체가 생기지 않는다. **2b-4 배포 이후는 다르다** — replay 행이 존재하는 상태의 롤백은 P24 의 별도 절차를 따른다.

---

## 5. 작업 항목

### 진행 상태

| PR | 항목 | 상태 |
|---|---|---|
| 2b-1 | P1 · P1-b · P2 · P3 · P4 · P5 · P6 · P7 | ✅ **완료** — diff 리뷰 3R(12건 전량 반영, 3R P1=0) · 918 tests 0 failed · 변이 8종 red |
| 2b-2 | P8 · **P9 · P9-b · P9-c** · P10 ~ P13 | 🔄 구현 완료 · 902 tests 0 실패(모듈별) · 변이 14종 red · lint 7종 green(parity self-test 18종) · **Codex diff 리뷰 미실행(quota)** |
| 2b-3 | P14 ~ P17 | 🔲 |
| 2b-4 | P18 ~ P25 | 🔲 |

### PR ④-c-2b-1 — 원장 축 확장 + incident 집계 정정

**P1.** `dead_letter_records` additive 마이그레이션 **4 DB** (order **V8** · product **V6** · payment **V6** · notification **V4**).
전부 nullable. 컬럼: `root_record_id BIGINT` · `publication_status VARCHAR(20)` · `outbox_event_id BIGINT` ·
`replay_deadline DATETIME(6)` · `replay_policy VARCHAR(120)`(정책 식별자+버전+판정을 담으므로 40 은 부족 — 구현 중 상향) · `last_replay_attempt_id VARCHAR(36)` ·
`last_replay_target_group VARCHAR(120)` · `resolved_at DATETIME(6)` · `resolved_by VARCHAR(120)` ·
`reopened_at DATETIME(6)` · `reopened_reason VARCHAR(500)`.
인덱스: `idx_dlr_root (root_record_id, status)` — root-only 집계용 · `idx_dlr_attempt (last_replay_attempt_id)` — P15 대조용.
**FK 는 걸지 않는다** — 자기참조 FK 는 자식 INSERT 와 root 잠금 순서를 한 트랜잭션 안에서 뒤집을 수 있고, 4 DB 에 같은 제약을 복제하는 비용 대비 얻는 게 없다.

**P2.** `DeadLetterStatus.RESOLVED` 추가 + `isTerminal()` = `DISCARDED || RESOLVED`.
`DeadLetterRecord.resolve(actor, evidence)` — **근거 문자열 필수**(공백이면 `IllegalArgumentException`),
`resolved_at`/`resolved_by`/`note` 기록. 이미 terminal 이면 `false`.

**P3.** 신규 적재 행의 **self-root** 설정 — `DeadLetterRecorder.record()` 가 `insertIfAbsent` 로 1을 받으면
같은 트랜잭션에서 그 행을 조회해 `root_record_id = id` 로 채운다.
> `INSERT ... SET root_record_id = LAST_INSERT_ID()` 를 쓰지 않는 이유: `INSERT IGNORE` 가 건너뛴 경우
> `LAST_INSERT_ID()` 는 **직전 성공 INSERT 의 값**을 그대로 돌려줘 **남의 행을 root 로 지목**한다.
> 조회 경로는 이미 존재한다(`findByClusterIdAnd...`, V10).

**P4.** 집계·purge 를 **incident(root) 단위**로 전환. `DeadLetterRecordJpaRepository`:
- `countUnresolved` · `findOldestUnresolvedOccurredAt` · `findStaleUnresolved` →
  `(root_record_id IS NULL OR root_record_id = id) AND status IN ('OPEN','ACKED')` — **expand 조건**(D6-3 1단계)
- `findPurgeable` → root 조건 + `status IN ('DISCARDED','RESOLVED')` + 종결 시각을
  **`CASE WHEN status='DISCARDED' THEN discarded_at WHEN status='RESOLVED' THEN resolved_at END < :threshold`**
  로 고른다.
  > **`COALESCE(discarded_at, resolved_at)` 을 쓰지 않는다 (리뷰 1R #10)**: `DISCARDED` → 재개방 → `RESOLVED` 를
  > 거친 root 는 **두 시각을 모두** 갖는다. `COALESCE` 는 항상 과거의 `discarded_at` 을 골라
  > **새 종결의 보존기간이 지나기 전에 삭제**한다. 감사 시각은 지우지 않고(재개방 이력이 사라지므로),
  > 대신 **현재 상태에 해당하는 시각**만 본다.
- purge 는 root 삭제 시 **자식을 함께** 삭제한다(`deleteByRootRecordId`). 자식 단독 purge 경로는 만들지 않는다
- **purge 도 root 를 `FOR UPDATE` 로 잠그고 상태·cutoff 를 재검사한 뒤 같은 트랜잭션에서 삭제한다 (2R #5)** —
  초안은 조회 후 삭제였다. 그 사이 P15 가 root 를 재개방하고 자식을 넣으면 **먼저 조회한 purge 가
  살아 있는 incident 와 새 자식을 지운다**. P5·P15 는 root 잠금으로 직렬화되는데 purge 만 그 규약 밖에 있었다

**P5.** **종결의 root 정규화·전파** (ADR §D5-4 "root 와 활성 자식에 원자적 전파" · 리뷰 1R #6).
> **구현 중 추가된 계약 (diff 리뷰 1R·2R)**: 요청 행과 purge 후보를 **엔티티가 아니라 id 로** 읽는다.
> 엔티티로 읽으면 영속성 컨텍스트에 적재되어 뒤의 `SELECT ... FOR UPDATE` 가 **잠금만 얻고 상태를
> refresh 하지 않고**, 자식은 잠그지 않으면 REPEATABLE READ **스냅샷**을 봐서 root 는 no-op 하면서
> 자식만 덮어쓴다. 그래서 `findRootIdOf`/`findPurgeableRootIds`(id projection) + `findChildrenForUpdate`
> (활성 자식만 `PESSIMISTIC_WRITE`)가 계약이다.
- 종결 요청의 대상 id 가 **자식이면 canonical root 로 정규화**한다(자식 단독 종결은 미결을 종결로 위장한다)
- root 를 `SELECT ... FOR UPDATE` 로 잠근 뒤 root 와 **활성 자식 전부**를 같은 트랜잭션에서 전이한다
- `acknowledge`/`resolve`/`discard` 셋 다 이 경로를 쓴다 — 하나라도 빠지면 축이 갈라진다

**P6.** `DeadLetterEndpoint` — `action=resolve` 추가(근거 필수) + `backlog()` 응답에 `publication` 축 요약 추가.
**I-1 가드는 여기 넣지 않는다** — `publication_status='REQUESTED'` 를 만드는 주체가 2b-4 에 생기므로
2b-1 의 가드는 vacuous 하다. I-1 은 **P21 과 같은 PR**에서 원자 조건으로 넣는다.

**P1-b.** (구현 중 추가) `dead-letter-schema-parity-lint` 를 **신규 마이그레이션까지** 확장하고
**java 복제 parity** 를 함께 검사한다.
> **P25 에서 앞당긴 이유**: 신규 파일을 만드는 PR 이 그 파일의 parity 를 검사하지 않으면 2b-2·2b-3 세 PR 동안
> 4벌이 갈라져도 아무 것도 실패하지 않는다. 기존 lint 는 `V*__dead_letter_records.sql` 만 보므로 신규 파일이
> **glob 에 걸리지 않아 조용히 통과**한다.
> java 7파일은 현재 4서비스에서 **byte 동일**이며(구현 전 확인), 이 PR 이 그 7파일을 전부 고친다 —
> 한 벌만 고치는 실수를 정적으로 막는다. P25 에는 `outbox_events` parity 와 진입점 단일성 검사가 남는다.

**P7.** 회귀 테스트 + E2E 게이트 갱신.
- 기존 미결 행(= `root_record_id IS NULL`)이 마이그레이션 **전후로 같은 건수**로 집계된다 (N9)
- `publication_status='PUBLISHED'` 인 root 가 backlog·oldest-age 에 **계속 잡힌다** (N5 — ADR 이 지목한 핵심 단언)
- `RESOLVED` 는 backlog 에서 빠지고 purge 대상이 된다. **`DISCARDED` → 재개방 → `RESOLVED` 교차 전이**의 purge 경계
- 자식 id 로 종결 요청 시 root 로 정규화되어 root+자식이 함께 전이된다
> **테스트 배치**: 신규 회귀 테스트는 **order-service** 에 둔다. `global/deadletter` java 7파일은 4서비스에서
> **byte 동일**이고 그 동일성을 P1-b lint 가 강제하므로, 4벌 테스트 복제는 같은 코드를 네 번 도는 비용만 든다.
> 나머지 3서비스는 **기존 원장 테스트가 회귀 없이 통과**하는 것으로 확인한다.
- **`scripts/e2e/saga_e2e.py:64-68` 의 `EXPECTED_MIGRATIONS` 를 `order 1~8 · product 1~6 · payment 1~6 · notification 1~4` 로 갱신**한다
  (리뷰 1R #14 — 이 상수는 **버전 집합 정확 일치**를 readiness 에서 강제하므로, 갱신하지 않으면 E2E 가 먼저 red 가 된다)

### PR ④-c-2b-2 — 발행 표면

#### 착수 전 코드 검증 (2026-09-02) — 계획이 바뀐 곳

> 원칙: 전제는 ADR 이 아니라 **현재 코드**다. 아래는 P8~P13 초안의 진술을 직접 grep/파일 확인한 결과다.

| # | 초안의 진술 | 확인 결과 | 처분 |
|---|---|---|---|
| C-1 | `OutboxPollingService:83-86`(ack→`markPublished`→save) · `:116`(실패 경로 재save) · `:119-124`(`event_type` 을 토픽으로) | **전부 정확**. `buildRecord` 는 `new ProducerRecord<>(event_type, null, aggregate_id, payload)` + trace/user 헤더 2종 | 유지 |
| C-2 | `OutboxEventJpaRepository:31-33` 이 `status='PUBLISHED' AND published_at < cutoff` 를 **무조건** 삭제 | 정확 (native `DELETE ... LIMIT :limit`) | 유지 |
| C-3 | 거짓이 되는 javadoc **2곳**(`OutboxEventCleanupScheduler:23` · `OutboxRetentionProperties:18-19`) | 정확하나 **4곳이 더 있다**(리뷰 A #1 로 2곳 추가) — `notification-service/application.yml`(“소비 전용(outbox 미소유)”) · `notification-service/build.gradle:27-28`(“cleanup 소유(processed only)”) · `build.gradle:31-32`(“소비 전용이라 outbox poller 가 없어”) · `NotificationApplication:12`. **`NotificationApplication:12` 는 P9 를 기다릴 것 없이 이미 거짓이다** — `DeadLetterMaintenanceScheduler:58·114` 가 이미 `@Scheduled` 2개를 갖는데 javadoc 은 `@EnableScheduling` 을 processed_events cleanup 전용으로 서술한다 | **P9 를 6곳으로 확대** |
| C-4 | `global/outbox` 8파일 복제 · `OutboxEventStatus` 는 payment 판본(`BACKFILL` 없음) | 정확. 8파일 중 **7개가 order↔product↔payment byte 동일**이고 `OutboxEventStatus` 만 갈린다(order/product 에 `BACKFILL` 있음) | 유지 |
| C-5 | “3서비스와 스키마 동일 — **parity lint 가 대조한다**” | **거짓**. `dead-letter-schema-parity-lint.sh` 는 `dead_letter_records` 전용이다 — `CREATE TABLE dead_letter_records` 추출 · `V*__dead_letter_records.sql`/`V*__dead_letter_replay_axis.sql` glob · `global/deadletter` java 9파일. **`outbox_events` 를 대조하는 검사는 존재하지 않는다** | **P9-b 신설** — 대조기를 만들지 않으면 notification 판본이 갈라져도 아무 것도 실패하지 않는다 |
| C-6 | P12 의 cleanup 제외 조건을 `NOT EXISTS (… d.outbox_event_id = o.id …)` 로 표기 | **그대로는 실행되지 않는다** — 다만 이유는 초안 정정이 처음 적은 것과 다르다(리뷰 A #2 가 반증, `mysql:8.0.46` 실측으로 확인). 실제 원인은 **alias `o` 를 선언한 적이 없다**는 것뿐이다 | **P12 SQL 정정** — 정규명 `outbox_events.id` (또는 `AS o` 선언). 아래 실측 매트릭스 참조 |
| C-7 | (초안 무언급) reconciler 는 `publication_status='REQUESTED'` 원장 행을 조회한다 | 해당 컬럼에 **인덱스가 없다**(V8 은 `(root_record_id, status)`·`(last_replay_attempt_id)` 만 생성) | **인덱스를 추가하지 않는다** — `dead_letter_records` 는 DLQ 유입량에 유계이고, 같은 컬럼을 스캔하는 `countUnresolvedByPublicationStatus`(2b-1)가 이미 무인덱스로 돈다. 4 DB 마이그레이션 + parity lint glob 확장 비용이 이득을 넘는다. **§10 R8 로 이연** |
| C-8 | P9 가 notification base yml 에 `cleanup.cron` 키를 선언 | `app.outbox.cleanup.cron` 은 **order/product/payment 어느 yml 에도 선언돼 있지 않다**(`@Scheduled` 인라인 기본값 `0 45 3 * * *` 만) | **선언하지 않는다** — notification 에만 선언하면 4벌이 갈라진다. P9 의 키 목록에서 제거 |
| C-9 | (초안 무언급) `OutboxPollingScheduler` 는 `@Scheduled(fixedDelay = 5000)` **리터럴**이다 | ④-d-2b P17 의 “주기를 타입 안전 properties 로, 인라인 기본값 금지” 계약은 **`app.scheduler.*`·`app.refund.*` 에만** 적용됐다(실측: outbox·deadletter·idempotency 스케줄러는 전부 인라인 유지) | ~~복제본도 그대로 둔다~~ → **CI 실패가 이 판단을 뒤집었다 (아래 “CI 후속” 참조)**. 주기를 `fixedDelayString` 으로 뺐다 — 배경 잡이 도는 상태에서는 발행 횟수를 세는 테스트가 성립하지 않는다. 운영 기본값 5s 는 불변 |
| C-10 | (초안 무언급) notification 에 poller 를 배선할 수 있는가 | `KafkaTemplate<String,String>`(DLQ recoverer 가 이미 사용) · `SlackPort` · `@EnableScheduling` · `shedlock` 테이블 **전부 존재** | 신규 인프라 0 |
| C-11 | (초안 무언급) sentinel/원장 id 가 컬럼 폭에 들어가는가 | 3서비스 `outbox_events` DDL byte 동일 · `event_type VARCHAR(50)`(`__replay__` 수용) · `aggregate_id VARCHAR(50)`(원장 id 문자열 수용) | 유지 |
| C-12 | (초안 무언급) ADR-0012 D1 표 갱신 | `0012:53` 이 아직 `| Notification | notifications | processed_events |` 이다. ADR-0020 D2 가 개정을 지시했으나 **미반영** | **P9-c 신설** — 이 PR 이 표를 참으로 만드는 순간이므로 Update Log 를 같은 PR 에서 단다 |
| C-13 | (초안 무언급) notification 이 **남의 토픽에 발행**하게 되는 근거 | ADR-0020 **D8**(`1 topic = 1 producer` 명시적 예외 · 프로비저닝 소유와 발행 권한 분리). `NewTopic` 소유는 그대로이므로 ADR-0011 D2 는 무영향 | P9·P11 에 근거 명시 |
| C-14 | (무언급) notification 이 outbox 를 갖게 되면 갱신이 필요한 **운영 표면**이 있는가 | **없다 — 스윕으로 확인**. `.github/workflows/ci.yml`·`k8s/**` 에 outbox 참조 0건(설정이 이미지 안 base yml 소유) · notification `application-k8s.yml`/`application-local.yml` 에 outbox·shedlock 키 0건(ADR-0007 정합) · `observability-promql-lint.sh` 의 유일한 outbox 문자열은 self-test 치환용이고 per-service alert 목록이 아니다 · ShedLock 락 이름은 3서비스가 각각 다른 값을 선언하고 있어 `notificationOutboxPollingJob` 추가에 충돌 없다(DB-per-service 라 `shedlock` 테이블 자체가 분리) · `docs/runbooks/dlq-recovery.md:213` 의 outbox 서술은 서비스 중립이라 참으로 남는다 | **범위 확대 없음**. 갱신 대상은 계획에 이미 있는 `EXPECTED_MIGRATIONS`(notification `1~5`) · `05:177`(P24) · ADR-0012 D1(P9-c) 뿐이다 |

**P8.** `outbox_events` additive 마이그레이션 **3 DB** (order **V9** · product **V7** · payment **V7**). 전부 nullable:
`record_kind VARCHAR(10)` · `destination_topic VARCHAR(120)` · `destination_partition INT` ·
`record_key VARCHAR(255)` · `source_record_timestamp BIGINT` · `replay_target_event_id VARCHAR(36)` ·
`replay_headers TEXT` · `replay_root_record_id BIGINT` · `target_consumer_group VARCHAR(120)`.
**`DEFAULT` 를 두지 않는다** (D3 — 기본값은 discriminator 누락을 조용히 삼킨다).
`EXPECTED_MIGRATIONS` → `order 1~9 · product 1~7 · payment 1~7 · notification 1~5`.

**P9.** notification outbox 신설 (D2). Flyway **V5** 로 `outbox_events` 를 **replay 컬럼 포함 상태로** 생성
(3서비스 `V1__init_*.sql` 의 DDL + P8 의 additive 컬럼을 합친 최종 형태 — 대조기는 P9-b 가 만든다) +
`global/outbox` 8파일 복제(`OutboxEventStatus` 는 payment 판본 = `BACKFILL` 없음, 나머지 7파일은 **byte 동일**) +
`OutboxRetentionProperties` 활성화. `@Scheduled` 인라인 기본값은 **그대로 복제한다** (C-9).
설정키 `app.outbox.*`(`retention` · `lock-name: notificationOutboxPollingJob` · `polling.*`)는
**`notification-service/src/main/resources/application.yml` (base) 소유**다 — 런타임 동작 정책이므로 ADR-0007 상
프로파일 배치가 금지된다. 프로파일에 중복·override 가 없음을 바인딩 테스트로 고정한다.
**`cleanup.cron` 은 선언하지 않는다 (C-8)** — 3서비스 어디에도 없는 키다.
`docs/05-data-design.md:177` 의 "notification 은 `processed_events` 만" 서술은 P24 에서 정정한다.
**소스 javadoc/주석 계약은 같은 PR 에서 고친다 (2R #13 · C-3 으로 6곳 확대)** — 이 PR 이 머지되는 순간
**거짓이 되는** 진술 5곳: `OutboxEventCleanupScheduler:23`("notification/user 제외") ·
`OutboxRetentionProperties:18-19`("product/order/payment 만 활성화") ·
`notification-service/application.yml`("notification 은 소비 전용(outbox 미소유) → outbox retention 없음") ·
`notification-service/build.gradle:27-28`("notification 은 cleanup 소유(processed only)") ·
`build.gradle:31-32`("notification 은 소비 전용이라 outbox poller 가 없어 미보유였음").
**+ 이미 거짓인 것 1곳**: `NotificationApplication:12` 가 `@EnableScheduling` 을 processed_events cleanup 전용으로
서술하는데 `DeadLetterMaintenanceScheduler:58·114` 의 `@Scheduled` 2개가 이미 그 위에 얹혀 있다(④-c-2a 이후).
**선재 결함이지만 같은 문장을 이 PR 이 또 건드리므로 함께 고친다** — 남겨두면 다음 사람이 "이 PR 이 만든 stale"
로 오독한다.
**발행 권한 근거는 ADR-0020 D8** — notification 은 자기가 프로비저닝하지 않은 토픽에 write 하게 된다.
`NewTopic` 소유는 옮기지 않으므로 ADR-0011 D2 는 무영향이다 (C-13).

#### CI 후속 (2026-09-03) — 테스트 3종의 결함 4건

PR [#102] 의 CI 에서 `OutboxAtLeastOnceIntegrationTest` 가 실패했다. **운영 코드 결함이 아니라 전부 내 테스트
하네스 결함**이었고, 원인을 확정하기까지 가설 4개가 실측으로 반증됐다.

| 가설 | 판정 |
|---|---|
| Mockito 재스터빙이 배경 `@Scheduled` 호출과 경합 | **참** — CI 실패(`RuntimeException at :105`)의 원인. 다만 이것만으로는 로컬 실패가 남았다 |
| 배경 스케줄러가 같은 행을 집어간다 | 껐는데도 실패 → **단독 원인 아님** |
| 첫 발행이 6s 타임아웃을 넘긴다 | `publish-timeout=30s` 로도 실패 → **반증** |
| 앱과 drain 이 서로 다른 Kafka 컨테이너를 본다 | 부트스트랩 주소 일치 실측 → **반증** |

**확정된 원인**: 실패 시 `brokerEndOffsets` 가 **전부 0** 이었다 — 소비를 못 한 게 아니라 **1사이클의 send 가
실제로 실패**한 것이다. 컨테이너가 여럿 뜬 느린 실행에서 **토픽 생성이 끝나기 전에 첫 send 가 나가** 메타데이터
대기로 타임아웃한다.

**도중에 내가 틀렸던 추론 2건도 남긴다**:
- *“`hasMessage("DB down")` 이 통과했으니 send 는 성공했다”* — **거짓**. send 가 실패해도
  `handlePublishFailure` 끝의 save 가 같은 예외를 덮어써 밖으로 나간다. 이 착각이 진단을 한 라운드 지연시켰다.
- *“배경 잡을 껐다”* — **거짓**. `fixedDelay` 는 **간격**만 정하고 첫 실행은 기동 직후 그대로 일어난다
  (로그의 `[scheduling-1] Unexpected error occurred in scheduled task` 가 증거).

**수정 4건** (세 테스트 클래스 공통):
1. **`awaitTopicReady`** — 측정 전 토픽·리더 준비 대기. 이 테스트가 재는 것은 재발행이지 토픽 프로비저닝이 아니다
2. **측정을 소비 → broker end offset 으로 전환** — group 조인·리밸런스·fetch 타이밍이라는 변수를 제거
3. **1사이클 ack 를 명시적으로 단언** — 이 단언이 없어 첫 발행이 조용히 사라져도 통과하고 **마지막 개수
   단언에서 엉뚱하게** 터졌다. 실제로 이 단언이 원인을 특정했다
4. `drain()` 을 `subscribe` → `assign` + `seekToBeginning`, Mockito 재스터빙 → `AtomicBoolean` 단일 answer

**검증**: 두 클래스 동시 실행 **6회 연속 통과**(6m20s·8m15s 짜리 느린 실행 포함 — 예전에 실패하던 것이 정확히
그 구간이다). notification 2종·parity lint(self-test 18종) green.

**P9-b.** `outbox_events` parity 대조기 (C-5). `dead-letter-schema-parity-lint.sh` 를 확장한다 —
신규 스크립트를 만들지 않는 이유는 대조 대상·판단 근거("4개 모듈에 흩어져 한 모듈의 테스트가 다른 모듈을 볼 수 없다")가
같고, CI 배선이 이미 있기 때문이다. 비교 축 3개:
1. **최종 스키마 동일성** — order/product/payment 는 `V1__init_*.sql` 의 `CREATE TABLE outbox_events` + P8 의
   `ALTER TABLE outbox_events`, notification 은 P9 의 `CREATE TABLE outbox_events`. **한 벌씩 다른 파일에서 오므로
   원문 해시가 아니라 "컬럼명 → 타입·nullability" 집합**으로 정규화해 비교한다(replay 축 ALTER 는 원문 해시였다 —
   그쪽은 4벌이 서로의 사본이라 성립했지만 여기서는 성립하지 않는다).
2. **P8 신설 9컬럼이 4서비스 전부에 존재하고 전부 nullable** (`DEFAULT` 절 부재도 함께 — D3)
3. **`global/outbox` java 7파일 byte 동일** + `OutboxEventStatus` 는 **order/product 동일 · payment/notification 동일**
   (2집합 계약을 lint 가 명시적으로 인코딩한다 — “전부 동일” 로 걸면 기존 상태가 곧바로 red 다)
4. **목록 자체를 검사한다 (구현 중 추가)** — `global/deadletter` 에서 *지금 4벌이 byte 동일한데 `java_files`
   목록에 없는* 파일을 `DLQ-PARITY-014` 로 잡는다. 구현 중 `DeadLetterPublicationReconciler` 를 4벌 복제해
   놓고 목록에 더하는 것을 잊었고, 그것은 **아무 것도 실패시키지 않아 드러나지 않았다** — ④-c-2b-1 의
   glob 미확장과 같은 구멍이 신규 파일에서 재발한 것이다. 사람의 기억 대신 검사가 목록을 지킨다.
   디렉토리 전체를 요구하지 않는 이유: `DeadLetterConsumer`/`KafkaConfig`/`QuarantineConsumer` 는 토픽·group 이
   서비스마다 정당하게 다르다. **이 검사가 기존 미등록 복제본 2개(`DeadLetterContainerGuard`·
   `DeadLetterKafkaConfig`, ④-c-2a 산출물)도 찾아내 목록에 편입**했다 — 계획에 없던 소폭 확대다

**P9-c.** ADR-0012 D1 표 갱신 (C-12). `0012:53` 의 Notification 행을
`processed_events` → `processed_events, outbox_events` 로 고치고 **Update Log** 에 근거(ADR-0020 D2)를 남긴다.
ADR 본문 수정은 Update Log 를 동반해야 하며 커밋은 `fix(adr):` 로 낸다(⑤ 의 ADR-0012 갱신 선례).
**이 PR 에서 하는 이유**: 표를 참으로 만드는 변경이 이 PR 이고, P24 로 미루면 3개 PR 동안 ADR 이 거짓을 말한다.

**P10.** `OutboxEvent.replay(...)` 팩토리 — `record_kind='REPLAY'` · `destination_topic`/`destination_partition`/
`record_key`/`source_record_timestamp`/`replay_target_event_id`/`replay_headers`/`replay_root_record_id`/
`target_consumer_group` 을 채우고, `event_type` 은 **`__replay__` sentinel**, `aggregate_type='DLQ_REPLAY'`,
`aggregate_id` = 원장 행 id 문자열(V4 의 NOT NULL 충족).
도메인 팩토리 `create(...)` 는 `record_kind='DOMAIN'` 을 **명시**한다.
> **sentinel 을 destination topic 으로 대체하지 않는 이유**: 구버전 poller 는 `event_type` 을 그대로
> 목적지 토픽으로 쓴다(`OutboxPollingService:119-124`). destination topic 을 넣어두면 롤백 시 구 poller 가
> **원장 id 를 key 로 업무 토픽에 조용히 발행**한다 — 파티션이 틀리고 fence 도 안 거친 발행이다.
> sentinel 이면 존재하지 않는 토픽으로 향해 **눈에 띄게 실패**한다. 롤백 절차는 P24 가 별도로 못박는다.

**P11.** poller kind 분기 — `buildRecord()` 를 `record_kind` 로 갈라
`new ProducerRecord<>(destinationTopic, destinationPartition, sourceRecordTimestamp, recordKey, payload, headers)`
를 조립한다. 헤더는 `replay_headers` allowlist JSON 에서만 만든다(표준 `DLT_*` **미탑재**).
**도메인 경로의 기존 `buildRecord()` 는 한 줄도 바꾸지 않는다.**

**P12.** reconciler — `publication_status` 전이 주체 1종. `REQUESTED` 인 원장 행의 `outbox_event_id` 로
outbox 상태를 조회해 `PUBLISHED`/`PUBLISH_FAILED` 로 전이한다. `@Scheduled` + `@SchedulerLock`.
**관리 API 는 `REQUESTED` 까지만 만든다**(D6-4).

**outbox cleanup 과의 경쟁을 닫는다 (2R #6)** — 현 cleanup 은 `status='PUBLISHED' AND published_at < cutoff` 인
행을 **무조건** 지운다(`OutboxEventJpaRepository:31-33`). reconciler 가 retention 이상 멈추면 replay outbox 가
먼저 삭제되고, root 는 **`REQUESTED` 에 영구 고착**된다 — I-1 때문에 종결도 못 한다. 둘을 함께 막는다:
1. cleanup 에서 **연결된 root 가 아직 `REQUESTED` 인 replay 행을 제외**한다 (원장과 outbox 는 **같은 서비스 DB**다).
   **상관 참조를 정규명으로 쓴다 (C-6)** — 초안의 `d.outbox_event_id = o.id` 는 **alias `o` 를 선언한 적이 없어서**
   깨진다. `mysql:8.0.46`(프로젝트 이미지) 실측 매트릭스:

   | 형태 | 결과 |
   |---|---|
   | `DELETE FROM outbox_events AS o … WHERE … o.id … LIMIT 1` | **성공** (1행 삭제) — 단일 테이블 DELETE 는 8.0.16+ 부터 alias 를 받는다 |
   | `DELETE FROM outbox_events … WHERE … o.id … LIMIT 1` (초안) | **ERROR 1054** `Unknown column 'o.id' in 'where clause'` |
   | `DELETE FROM outbox_events … WHERE … outbox_events.id … LIMIT 1` (채택) | **성공** (1행 삭제) |
   | `DELETE o FROM outbox_events o … LIMIT 1` | **ERROR 1064** — multi-table 형식은 `LIMIT` 을 지원하지 않는다 |

   즉 `AS o` 선언도 유효한 선택지다. **정규명을 택하는 이유는 문법 제약이 아니라** 이 파일이 4서비스 byte 동일
   복제라 diff 를 최소로 두기 위함이다. 세 번째 행의 실행에서 `publication_status='REQUESTED'` 로 연결된 행이
   실제로 **살아남는 것**까지 함께 확인했다:
   ```sql
   DELETE FROM outbox_events
    WHERE status = 'PUBLISHED' AND published_at < :cutoff
      AND NOT EXISTS (SELECT 1 FROM dead_letter_records d
                       WHERE d.outbox_event_id = outbox_events.id
                         AND d.publication_status = 'REQUESTED')
    LIMIT :limit
   ```
   이 파일은 4서비스 byte 동일 복제이므로 **notification 판본을 포함해 4벌을 함께 고친다**(P9-b 축 3이 강제한다)
2. **outbox 부재를 `PUBLISH_FAILED` 로 추론하지 않는다 (3R #3)** — 행 부재는 실패의 증거가 아니다.
   **이미 발행된 행**이 레거시 cleanup·수동 삭제·정합성 결함으로 사라져도 똑같이 관측된다. 자동 강등하면
   **발행된 사건을 "실패" 로 감사 기록하고 재요청까지 열어준다**. ADR §D6-4 는 outbox 가 **실제 `FAILED` 로
   소진된 경우에만** 그 전이를 정의한다.
   → 부재는 **fail-closed 경보 + 운영자 판정 대상**으로 둔다. 1의 제외 조건이 정상 경로에서 부재를 만들지
   않으므로, 부재가 관측되면 그것 자체가 **계약 위반 신호**다. 이 상태의 종결 경로는 §10 **R7** 로 남긴다

**P13.** D1 검증 — **실제 DB + Kafka 통합테스트**로 at-least-once 를 관측한다.
> **리뷰 1R #11 이 초안을 반증했다**: "`save` 스텁 예외 1회" 로는 중복 발행이 일어나지 않는다.
> `OutboxPollingService:85-86` 이 `markPublished()` 로 **인메모리 상태를 이미 PUBLISHED 로 바꾼 뒤** save 하고,
> 실패하면 `handlePublishFailure` 가 `:116` 에서 **같은 객체를 다시 save** 한다 — 두 번째 save 가 성공하면
> `PUBLISHED` 가 그대로 저장되어 재발행이 없다. 초안의 검증은 **주장을 관측하지 못하는 설계**였다.

따라서: 해당 사이클의 **두 save 를 모두 실패**시키고 → DB 재조회로 행이 `PENDING` 임을 확인 → 장애 복구 후
다음 poll 실행 → **broker 에 서로 다른 2개 레코드**가 있고 **같은 `eventId` 의 `processed_events` 증가분이 1**임을 확인한다.

### PR ④-c-2b-3 — 재실패 상관 + 재개방

**P14.** replay 상관 헤더 — 발행 측(P11) allowlist 4종: `pc-replay-attempt-id`(UUID) ·
`pc-replay-ledger-owner`(서비스) · `pc-replay-target-group` · `pc-replay-root-id`.
판독 측: `DlqOrigin` 에 상관 필드 4개 추가 + `DlqHeaders.parse()` 가 **allowlist 키만** 읽는다(V9 의 화이트리스트 구조 유지).
> **`record_kind=REPLAY` 는 헤더로 판정하지 않는다 (리뷰 1R #3)**: 헤더 값은 조작 가능하고, outbox 행은
> `PUBLISHED` cleanup 으로 먼저 사라질 수 있어 정본이 될 수 없다(D5-4 수명 경쟁). 대조 정본은 **원장**이다.
>
> **다만 "attempt 기록이 있다" 만으로는 부족하다 (2R #4)**: 유효한 attempt 가 살아 있는 동안 **같은 헤더를 붙인
> 도메인 레코드**가 그 토픽·group 에서 실패하면 owner/group/topic 대조를 전부 통과한다. ADR §D5-4 가 요구한
> `record_kind=REPLAY` 대조를 "attempt 존재" 로 치환한 것은 **동치가 아니었다**.
> 따라서 root 에 **replay 대상의 durable fingerprint** 를 함께 보존하고 그것과 대조한다 —
> root 는 이미 `event_id`·`original_key`·`original_timestamp`·`origin_topic` 을 갖고 있으므로
> **자식의 그 4값이 root 와 일치**해야 상관한다(재발행분은 원본과 같은 key·payload·eventId·timestamp 를 싣는다 — D8-3).
> 이 대조는 outbox 수명과 무관하게 성립한다.

**P15.** `DeadLetterRecorder` 원자 대조 — 자식 적재 트랜잭션 안에서
① 헤더 `attempt-id` 로 **자기 서비스 원장의 `last_replay_attempt_id`** 가 일치하는 root 를 찾고
② `SELECT ... FOR UPDATE` 로 잠근 뒤
③ **ledger owner(현재 서비스)** · **실제 DLT consumer group ↔ `last_replay_target_group`** ·
`destination_topic ↔ origin_topic` · 헤더 `root-id ↔ root.id` ·
**fingerprint 4값 — 자식의 `event_id`·`original_key`·`original_timestamp`·`origin_topic` 이 root 와 일치**
(`original_key` 는 nullable 이므로 **null-safe 일치**로 비교한다: 둘 다 NULL 이면 일치) 를 **전부** 대조하고
④ 통과하면 자식 INSERT(`root_record_id = root.id`) + root 가 terminal 이면 **재개방**(`status='OPEN'`,
`reopened_at`/`reopened_reason` 기록, **기존 `resolved_at`/`discarded_at` 은 지우지 않는다** — 감사 이력이고
purge 는 P4 의 `CASE` 로 현재 상태 시각만 본다)를 **같은 트랜잭션**에서 수행한다.
**Slack 알림은 트랜잭션 밖 best-effort 다 (2R #11)** — 외부 webhook 은 rollback 대상이 아니므로
"원자적 · 정확히 1회" 는 성립하지 않는다. 기존 계약(`DeadLetterRecorder:20-23` — 내구적 신호는 원장 행)을 그대로
따르고, **commit 후 시도 · 0회 가능**을 명시한다.
하나라도 어긋나면 **상관하지 않고 독립 root 행**으로 적재한다.

**P16.** 음성·경쟁 테스트 — 위조 attempt · 타 서비스 소유 attempt · 존재하지 않는 attempt ·
attempt 기록 없는 root(= replay 아님) · **유효 attempt + 다른 group** · **유효 attempt + 다른 root** ·
**다른 destination topic** · **유효 attempt + fingerprint 불일치**(같은 헤더를 붙인 도메인 레코드 —
`event_id`/`original_key`/`original_timestamp` 중 하나가 root 와 다름, 2R #4) → **8종** 전부 **독립 행**이 되어야 한다. 대조 조건을 하나씩 제거하면 해당 케이스만 red.
추가로 **수명 경쟁**: outbox `PUBLISHED` cleanup 을 먼저 돌린 뒤 지연 DLQ 적재를 주입해
**같은 root 로 상관되고 backlog 가 1** 임을 확인한다(N11).

**P17.** DLT 계약 고정 — `DlqIntegrationTest` 계열에 **정상 DLT 유입에서 `original_timestamp` 가 원장까지 저장됨**을
단언으로 추가한다(현재 미단언, ADR C1). **이것은 계약 고정이지 NULL 비율 측정이 아니다** — 측정은 P22 소관이다.

### PR ④-c-2b-4 — 좌표 reader + 진입점 + fence + backfill + 문서

**P18.** `OriginalRecordReader` (신규, `common`). `AdminClient` 로 `cleanup.policy`·`retention.ms` describe +
`beginning/end offset` 조회 → 요청 좌표가 `[beginning, end)` 밖이면 **불가**. 이어서 전용 consumer 로
`assign → seek → poll` 하고 **반환 레코드의 offset == 요청 offset** 을 검증한다(compaction hole 방어).
consumer group 을 만들지 않도록 `assign` 만 쓰고 offset 을 커밋하지 않는다.

**P19.** `ReplayEligibility` — §D5-2 **6 금지축을 독립 조건으로** 평가하고 **전부** 사유를 모아 반환한다
(첫 번째에서 멈추면 운영자가 한 번에 한 축만 본다).

- **`replay_deadline` 계산 (정정 — 리뷰 1R #1)**:
  1. `original_timestamp > now + clockSkewBudget` 이면 **거부**(허용 한도를 넘는 미래값)
  2. 통과분은 **`now` 로 clamp**: `replay_deadline = min(original_timestamp, now) + dlqReplayWindow`
  > 초안은 `min(original_timestamp, now + clockSkewBudget) + window` 였고, 이는 **4분 미래인 timestamp 에
  > 안전창을 4분 연장**한다. ADR §D5-3 은 "허용된 미래값은 `now` 로 clamp 해 계산한다(창을 늘려주지 않는다)"로
  > 명시돼 있다 — 초안이 ADR 과 충돌했다.
  3. root 에서 1회 계산하고 자식·재시도는 **상속**한다. 재계산 경로를 만들지 않는다 (N4)
  4. **만료 강제 (2R #3)** — `now >= replay_deadline` 이면 **거부**한다. 초안은 식과 상속만 정의하고
     ADR §D5-3 의 필수 조건인 `now < replay_deadline` 강제를 작업·검증 어디에도 넣지 않았다 —
     **만료된 사건을 발행해도 전부 green** 이었다
- **`replay_policy` 는 default-deny 레지스트리다 (정정 — 리뷰 1R #9)**: 초안은 "정책이 비어 있으면 허용" 이었으나,
  그러면 ADR §D5-4 가 `replay_policy` 에 담기로 한 **늦은 이벤트 위험이 아무 데서도 관리되지 않는다**.
  코드 레지스트리에 **업무 토픽 10종의 정책을 명시**하고 **미등록 토픽·eventType 은 거부**한다.
  정책은 *허용 여부* + *상태 사전조건*을 담고, 사전조건이 있는 토픽은 도메인 상태 조회를 거쳐 갈린다.
  - **초기 정책표를 여기서 확정한다 (3R #5)** — 2R 은 "2b-4 착수 전 확정" 으로 미뤘는데, 그러면 계획이
    정책 축을 **여전히 비워 둔 채** 완료 판정으로 간다. 업무 토픽 10종(`DlqTopology` 의 발행 토픽 전수):

    | 토픽 | 초기 판정 | 상태 사전조건 |
    |---|---|---|
    | `order.created` | **deny** | 주문 생성은 하류 예약·결제를 연쇄 개시한다. 늦은 재적용이 이미 취소된 주문의 재고를 다시 잡는다 |
    | `order.cancelled` | **allow** | 취소는 멱등 종결이고 늦게 도착해도 상태를 되돌리지 않는다 |
    | `order.compensation.requested` | **allow** | 보상 요청은 원장(`order_compensations`)이 종결 축을 따로 갖는다 |
    | `product.updated` | **allow** | 가격 캐시 갱신. 늦은 값은 다음 갱신이 덮는다 |
    | `stock.reservation.result` | **allow + 사전조건** | **주문이 아직 그 예약 결과를 기다리는 상태인지**를 도메인 조회로 확인한다 (← 실제 adapter 를 갖는 토픽) |
    | `stock.compensation.requested` | **allow** | 보상 요청. 원장이 종결 축 소유 |
    | `payment.requested` | **deny** | 외부 PG 승인을 유발할 수 있다. 늦은 재적용은 이중 과금 경로다 |
    | `payment.completed` | **allow** | 완료 통지. 소비 측이 전부 멱등 |
    | `payment.failed` | **allow** | 실패 통지. 멱등 |
    | `payment.refunded` | **allow** | 환불 통지. `payment_refunds` 가 종결 축 소유 |

    **미등록 토픽·eventType 은 거부**한다(default-deny). 이 표는 **초기값**이며 운영 경험에 따른 정밀화는 §10 R4.
  - **판정 결과를 allow·deny **양쪽 다** 기록한다 (3R #5)** — deny 는 P19 에서 걸러져 claim(P21)에 도달하지
    않으므로, claim 에만 기록하면 **거부 이력이 남지 않는다**. 판정은 claim 과 **독립된 감사 write** 로 남기고
    (root 의 `replay_policy` = *정책 식별자 + 버전 + 판정*), allow 인 경우 자식이 상속한다

**P20.** D8 fence — 발행 직전 최종 검사. `destination_topic == origin_topic` · `destination_partition ==
검증된 origin_partition` · `key`·`payload`·`eventId` 가 **reader 가 읽은 원본과 byte 동일** · `record_kind=REPLAY` ·
**요청 서비스 == 원장 소유 서비스**(타 서비스 원장 행 요청은 거부). 하나라도 어긋나면 outbox 행을 만들지 않는다.

**P21.** `DeadLetterEndpoint` `action=replay` — `POST /actuator/deadletter/{id}` body `{"action":"replay","actor":"..."}`.

**발행 축의 대상 행(target)과 상관 앵커(root)를 분리한다 (3R #1)** — 이것이 없으면 **두 번째 replay 가 불가능**하다.
첫 replay 가 성공하면 P12 가 root 의 `publication_status` 를 `PUBLISHED` 로 올리는데, claim 은
`NULL`/`PUBLISH_FAILED` 만 허용하므로 재실패 후 재요청이 **거부**된다 — V-16 의 "3회 replay" 가 성립하지 않았다.

| 축 | 어느 행에 쓰나 |
|---|---|
| `publication_status` · `outbox_event_id` | **target row** = incident 의 **가장 최근 활성 행**(자식이 없으면 root, 있으면 최신 `OPEN` 자식) |
| `last_replay_attempt_id` · `last_replay_target_group` · `replay_deadline` · `replay_policy` | **canonical root** (상관 앵커는 하나여야 P15 가 찾을 수 있다) |

**잠금 순서는 canonical root → target child 로 고정**한다(P5·P15·purge 와 같은 진입 순서라 순환이 없다).
**I-1 은 incident 단위로 본다** — root 또는 **활성 자식 중 하나라도** `REQUESTED` 면 종결을 거부한다.

**트랜잭션 순서를 못박는다 (리뷰 1R #3·#4)** — 한 트랜잭션 안에서:
1. root 를 `SELECT ... FOR UPDATE` 로 잠그고 이어서 target row 를 잠근 뒤 적격성(P19)·fence(P20) 검사
2. **조건부 claim UPDATE (target row)** — `SET publication_status='REQUESTED' WHERE id=:target
   AND (publication_status IS NULL OR publication_status='PUBLISH_FAILED')
   **AND status IN ('OPEN','ACKED')`**
   → 영향 행 0 이면 **거부하고 즉시 반환**(아직 outbox 를 만들지 않았으므로 orphan 이 없다)
2b. **root 앵커 UPDATE** — `SET last_replay_attempt_id=:attempt, last_replay_target_group=:group,
   replay_policy=:policy, replay_deadline=COALESCE(replay_deadline, :calculated) WHERE id=:root`
   > **`COALESCE` 가 계약이다 (3R #4)**: 초안은 "root 에서 1회 계산하고 상속" 이라고만 적고 **그 값을 어디에
   > 영속하는지 정의하지 않았다** — 구현자가 매 요청마다 transient 계산해도 V-11·V-13b 가 green 이었다.
   > 첫 claim 이 값을 박고 이후 요청은 **덮어쓰지 않는다**. 자식 INSERT(P15)는 root 의 값을 **복사**한다.
   > **사건 상태 조건이 필수다 (2R #2)**: publication 축만 보면 resolve 가 먼저 잠금을 얻어 `RESOLVED` 를
   > 커밋한 뒤 replay 가 `publication_status IS NULL` 조건으로 `REQUESTED` 를 기록해 ADR 이 금지한
   > **`REQUESTED` + terminal 조합**이 만들어진다. 초안은 replay 가 먼저인 순서만 막았다.
3. outbox INSERT (`OutboxEvent.replay(...)`)
4. root 에 `outbox_event_id` 기록
> 초안은 `outbox_event_id` 를 조건부 UPDATE 에 넣었는데, 그 값은 **auto-increment 라 INSERT 후에야 존재**한다.
> 그러면 outbox 를 먼저 만들 수밖에 없고, 경합 패자가 영향 행 0 을 정상 응답으로 돌려주면
> **먼저 만든 outbox 가 남아 두 번 발행**된다. claim 을 먼저 하고 id 를 나중에 채우는 순서로 바꾼다.
> 또한 초안에는 **`last_replay_attempt_id`/`last_replay_target_group` 을 쓰는 주체가 없었다** — P15 의 대조가
> 비교할 정본을 갖지 못했다. claim UPDATE 가 그 둘을 함께 기록한다.

**I-1 가드도 원자화한다 (리뷰 1R #5)**: `resolve`/`discard` 의 종결 UPDATE 자체에
**`AND (publication_status IS NULL OR publication_status <> 'REQUESTED')`** 를 넣는다.
순차 검사(`findById` → 엔티티 전이, 현 `DeadLetterEndpoint:68-84`)만 두면 resolve 가 NULL 을 읽은 뒤
replay 가 `REQUESTED` 를 커밋하고, 그 다음 resolve 가 terminal 을 저장할 수 있다.
> **`<> 'REQUESTED'` 만 쓰면 안 된다 (2R #1)**: SQL 에서 `NULL <> 'REQUESTED'` 는 **UNKNOWN** 이라
> `publication_status IS NULL` 인 행 — 즉 **replay 를 한 번도 요청하지 않은 절대다수** — 이 종결되지 않는다.
> ADR §D6-2b 표는 `NULL` 에서 종결을 **허용**한다. 초안 조건은 그 표와 정면으로 어긋났다.

**P22.** `original_timestamp` NULL 비율 **실측 증적** (정정 — 리뷰 1R #13).
ADR §D5-2 가 미측정이라고 지적한 것은 **네 실제 원장 DB 의 기존 행 분포**다. fixture 를 세는 것은 자기대조라
가용성 손실을 측정하지 못한다. 서비스별 원장에 **read-only 집계 SQL** 을 실행해 *전체 / `RESOLVED_ORIGIN` /
replay 후보* 각각의 **분자·분모·기준시각**을 `docs/progress/evidence/` 에 남긴다.
**운영 데이터가 0건이면 "표본 0 — 미측정" 이라고 그대로 기록한다** (0건을 "NULL 0%" 로 적지 않는다).

**P23.** backfill 마이그레이션 (D3·D6-3 3단계) — **order V10 · product V8 · payment V8 · notification V6**.
`UPDATE dead_letter_records SET root_record_id = id WHERE root_record_id IS NULL` ·
`UPDATE outbox_events SET record_kind='DOMAIN' WHERE record_kind IS NULL` + **NULL 잔여 0 검증**(잔여가 있으면 실패).
재실행 안전(idempotent)해야 한다. `EXPECTED_MIGRATIONS` → `order 1~10 · product 1~8 · payment 1~8 · notification 1~6`.
집계 조건의 `IS NULL` 분기는 **남겨둔다** — `NOT NULL` contract 는 이번 범위가 아니다(§10 R1).

**P24.** 문서 + **replay 개방 후 롤백 절차**(신규 — 리뷰 1R #15).
- `docs/runbooks/dlq-recovery.md` **§6 재작성**("재발행 — 현재 불가" → 절차·금지축·fence·재개방)
- **§6-R 롤백 절차**: ① 진입점 차단 → ② `record_kind='REPLAY' AND status='PENDING'` 잔여 확인 →
  ③ 잔여가 0이 될 때까지 **replay-aware poller 를 유지**(drain) → ④ 그 후에만 구버전 이미지 복귀.
  **③ 을 건너뛰면 구 poller 가 `event_type='__replay__'` 를 토픽으로 써서 발행이 깨진다**(P10 sentinel 근거)
- **절차를 강제하는 수단을 함께 만든다 (2R #9)** — runbook 은 프로세스 기동을 막지 못하므로 문서만으로는
  V-28 이 false-green 이다:
  1. **kill-switch** `app.dead-letter.replay.enabled`(기본 true, base `application.yml` 소유 — ADR-0007) — false 면
     `action=replay` 가 거부된다. **즉시 반영되지 않는다 (3R #7)** — Spring 정적 설정이라 ConfigMap 갱신 +
     **롤링 재기동**이 필요하다(현 `k8s/base/services/*/configmap.yml` 은 `SPRING_PROFILES_ACTIVE` 만 담고
     Deployment 가 `envFrom` 으로 읽는다). runbook 은 "설정 변경 → 재기동 → 반영 확인" 3단계를 그대로 적는다.
     그 사이의 요청 차단은 **운영 규율**이지 코드 강제가 아님을 명시한다
  2. **pre-deploy preflight** `scripts/replay-drain-preflight.sh`(신규) — **구 이미지 배포 명령 앞단**에서
     4 DB 를 조회해 `record_kind='REPLAY' AND status='PENDING'` 이 하나라도 있으면 **exit≠0**.
     - 배포 대상 이미지가 **replay-aware 인지 판별**해, aware 면 통과시키고 **구 이미지만** 막는다
     - DB 접속·권한 실패는 **fail-closed**(막는다). 4개 격리 DB 각각의 접속 정보가 필요하다
     - `scripts/rollout-convergence-gate.sh` 는 **배포 후** 수렴 게이트라 이 역할을 대신할 수 없다
- **ADR-0020 Update Log 기재 (3R #2)** — D5-4 의 상관 대조 항목 `record_kind=REPLAY` 를 **root 의 durable
  fingerprint 4값 대조로 대체**한 것은 계약 변경이다(수명 경쟁 때문에 outbox 를 정본으로 쓸 수 없다는 D5-4
  자신의 논거에서 따라 나온다). ADR 본문은 immutable 이므로 **Update Log 에 대체 사유와 범위를 남긴다**
- `StockReservationService` javadoc 의 "DLQ 재발행(새 eventId)" 을 **ADR-0012 D5 우회 경로**로 정정
- `docs/progress/PHASE4.md` 동일 서술 정정 · `docs/05-data-design.md`(notification outbox·신규 컬럼) ·
  `docs/02-architecture.md` notification 패키지 트리 · `grafana-alerts.yml` 주석(집계 단위 = incident, V14)

**P25.** lint 확장 2종.
- `dead-letter-schema-parity-lint` — 4 DB `dead_letter_records` 신규 컬럼 + 4 DB `outbox_events` replay 컬럼 parity
- **진입점 단일성 검사**(N14) — replay 를 개시하는 코드가 `DeadLetterEndpoint` 밖에 없고, runbook·리허설 스크립트에
  `UPDATE dead_letter_records SET status` 류 직접 상태 변경이 **0건**임을 정적으로 검사한다
  (④-c-2a 에서 실제로 났던 결함이다)

---

## 6. 검증 방법

**원칙**: "존재한다"·"배선됐다" 는 검증이 아니다. **실패를 주입하고 DB/상태로 확인**한다.
모든 기대값은 ADR-0020 본문 값을 **리터럴로 독립 기재**한다 (N17).

| # | 대상 | 주입할 실패 | 확인 지점 |
|---|---|---|---|
| **V-1** | N1 | poller 가 `destination_partition` 을 무시하도록 변이. **fixture 는 원본을 key 의 기본 hash 파티션과 다른 파티션에 기록**한다 (2R #8 — 같은 key 를 보존하므로 기본 partitioner 가 원본과 같은 파티션을 골라 변이가 검출되지 않는다) | 발행된 레코드의 partition ≠ 원본 → **red**. 복원 시 green |
| **V-2** | N1 | `source_record_timestamp` 미탑재로 변이 | **발행된 Kafka 레코드의 timestamp == 원본 timestamp** 를 직접 비교(3R #6 — deadline 연장으로는 검출되지 않는다. P19 가 자식 재계산을 금지해 상속되므로 timestamp 를 빼도 deadline 이 늘지 않는다). 추가로 재실패 DLT 의 `DLT_ORIGINAL_TIMESTAMP` == root 값 |
| **V-3** | N1 | `destination_topic` 을 다른 업무 토픽으로 변이 | P20 fence 가 거부. fence 제거 시 잘못된 토픽으로 발행되어 red |
| **V-4** | N1 | `record_key` bytes 1바이트 변이 | fence 가 원본 대조에서 거부 |
| **V-5** | N1 | `payload` bytes 1바이트 변이 | fence 가 거부 |
| **V-6** | N1 | **원장 `event_id` 만** 원본 payload 의 eventId 와 다르게 만든 fixture (2R #10) | fence 가 **요청 자체를 거부**한다. eventId 대조를 제거하면 요청이 승인되고 **outbox 행이 생긴다** → red. (3R #9 — 소비 멱등은 여기서 깨지지 않는다. reader 가 읽은 **원본 payload** 를 그대로 발행하므로 소비자가 보는 eventId 는 원본 그대로다. 멱등 보존은 별도 테스트 소관) |
| **V-7** | N2 | compaction hole 흉내 — 요청 offset 에 레코드가 없는 상태 구성 후 seek | reader 가 다음 레코드를 반환 → **offset 불일치로 거부**. 검증 제거 시 엉뚱한 메시지가 발행됨을 red 로 확인 |
| **V-8** | N3 | 6 금지축을 **각각 1개씩** 만족하는 원장 행 6개 | 각 행이 발행에 도달하지 않고 반환 사유가 **해당 축을 정확히 지목**. 축 하나를 검사에서 빼면 그 케이스만 red |
| **V-9** | N3 | `replay_policy` **미등록** 토픽으로 요청 | default-deny 로 거부 (P19 정정분) |
| **V-10** | N4 | `original_timestamp = now + 4m` / `now + 6m` 두 케이스, 고정 Clock | 4m → deadline == `now + 7d`(clamp 적용, 연장 없음) · 6m → **거부**. clamp 를 `now+skew` 로 되돌리면 4m 케이스가 `now+7d+4m` 이 되어 red |
| **V-11** | N4 | 재발행 → 재실패 → 자식 생성 → 재요청 | ① root 의 `replay_deadline` 이 **DB 에 영속**되고 2회차 claim 이 그 값을 **덮어쓰지 않는다**(`COALESCE`) ② 자식이 root 값을 **복사**했다. 두 단언을 분리한다 — 합치면 transient 계산으로도 green (3R #4) |
| **V-12** | N5 | root 를 `publication_status='PUBLISHED'` 로 두고 backlog 조회 | `dlq.backlog` == 1, `dlq.oldest.age` > 0. 조기 종결 코드를 넣으면 0 이 되어 red |
| **V-13** | N6 | `publication_status` **네 상태 전수**(`NULL`·`PUBLISHED`·`PUBLISH_FAILED`·`REQUESTED`)에 `resolve`/`discard` | 앞 셋은 **성공**, `REQUESTED` 만 거부. 조건을 `<> 'REQUESTED'` 로 되돌리면 **`NULL` 행이 종결 불가**가 되어 red (2R #1) |
| **V-13b** | N4 | `now = deadline - 1ms` / `= deadline` / `= deadline + 1ms` 고정 Clock | 앞은 허용, 뒤 둘은 **거부**. 만료 검사를 제거하면 red (2R #3) |
| **V-14** | N6 | replay 와 resolve 를 **latch 로 두 순서 각각** 실행 — ① replay 선점 ② **resolve 선점** | 어느 순서에서도 `REQUESTED` + terminal 조합이 생기지 않는다. claim 의 `status IN ('OPEN','ACKED')` 를 빼면 **② 에서만** red (2R #2) |
| **V-15** | N7 | root 를 `RESOLVED` 로 닫은 **직후** 늦은 자식 유입 | root 가 `OPEN` 으로 재개방 + `reopened_at` 기록. 자식은 **삭제·거부되지 않는다**. 알림은 **commit 후 callback 1회 시도**만 단언한다 |
| **V-15b** | N7 | 재개방 commit 직후 **알림 callback 실패**(또는 프로세스 중단) | 원장의 재개방은 **유지**되고 알림 **0회가 허용**된다 (3R #8 — 기존 best-effort 계약 `DeadLetterRecorder:20-23` 과 정합. "정확히 1회" 를 요구하면 계약이 두 개가 된다) |
| **V-16** | N8 | 같은 사건을 **3회** replay → 3회 재실패 (**첫 발행이 성공한 뒤의 2·3회차 포함**) | 원장 행 4(root+자식3), **backlog = 1**. root 종결 → 0. target row 분리를 되돌리면 **2회차 claim 이 거부되어** red (3R #1) |
| **V-17** | N8 | 자식 id 로 `resolve` 요청 | root 로 정규화되어 root + 활성 자식이 함께 종결. 자식만 닫히면 red |
| **V-18** | N9 | 마이그레이션 **전** 미결 3건 적재 → 마이그레이션 → 집계 | 전후 건수 동일(3). 조건을 `root_record_id = id` 로 곧바로 바꾸면 0 이 되어 red |
| **V-19** | N10 | **8종** 위조 상관 헤더(P16) — **유효 attempt + fingerprint 불일치** 포함 | 전부 독립 root 행. 대조 조건을 하나 빼면 해당 케이스가 잘못 상관되어 red |
| **V-20** | N10 | **소유 fence 는 아키텍처 테스트로 고정한다** (2R #12 — endpoint 는 숫자 id 만 받고 자기 datasource 만 조회하므로 "타 서비스 행 id" 라는 입력이 표현되지 않는다. 같은 숫자가 로컬에 있으면 로컬 행이 선택될 뿐이다) | `DeadLetterEndpoint` 가 타 서비스 datasource·repository 에 접근하지 않음을 정적으로 검사 |
| **V-21** | N11 | outbox cleanup 선행 실행 후 지연 DLQ 적재 | 같은 root 상관 + backlog 1 |
| **V-21b** | N11 | **reconciler 장기 중단 → cleanup 실행 → reconciler 복구** | cleanup 이 해당 replay outbox 를 **건너뛴다**(제외 조건). 행이 남아 있어 reconciler 복구 시 정상 전이. 제외 조건을 빼면 red (2R #6) |
| **V-21d** | N11 | outbox 행을 **강제로 삭제**한 뒤 reconciler 실행 | **`PUBLISH_FAILED` 로 강등되지 않고 자동 재요청도 열리지 않는다**. fail-closed 경보만 발생 (3R #3 — OR 조건 하나로 묶으면 정상 제외 경로만 통과해도 green 이 되어 강등 분기를 전혀 실행하지 않는다) |
| **V-21c** | N8 | purge 가 root 를 **조회한 뒤 삭제 전에** 재개방을 끼워 넣는다 | 재개방된 root 와 새 자식이 **삭제되지 않는다**. purge 의 `FOR UPDATE` + 상태 재검사를 빼면 red (2R #5). **재개방 경로가 2b-3 P15 에 생기므로 이 행의 검증도 2b-3 에서 수행한다** — 2b-1 은 잠금·재검사 코드만 넣고, 그 코드 자체는 이 시점에 직접 관측되지 않는다(미충족으로 명시) |
| **V-22** | N12 | 같은 원장 행에 replay 요청 **동시 2회** | outbox 행 정확히 1개, 한쪽 거부. **orphan outbox 0** 을 DB 조회로 확인 |
| **V-23** | N13 | broker ack 성공 후 아무 조작 없이 대기 | `status` 가 `OPEN`/`ACKED` 유지. 자동 `RESOLVED` 없음 |
| **V-24** | N14 | 진입점 단일성 lint (P25) | replay 개시 코드가 `DeadLetterEndpoint` 밖에 있거나 runbook 에 직접 상태 변경 SQL 이 있으면 red |
| **V-25** | N15 | notification 원장 행 1건으로 replay 개시 | notification `outbox_events` 에 replay 행 생성 → 원본 토픽으로 발행 |
| **V-26** | N16 | `record_kind` 를 채우지 않는 **팩토리 우회 INSERT** | 아키텍처 테스트가 실패한다. **런타임 DB 제약이 아님을 명시**(§10 R1) |
| **V-27** | D1 | ack 후 해당 사이클의 **두 save 모두** 실패 주입 (실제 DB+Kafka) | 행이 `PENDING` 으로 남고, 복구 후 다음 poll 에서 **broker 레코드 2개** + 같은 `eventId` 의 `processed_events` 증가분 **1** |
| **V-28** | 롤백 | replay `PENDING` 잔여가 있는 상태에서 **구 이미지 배포 명령을 실제로 실행** | **preflight 게이트가 배포를 실패시킨다**(문서 절차 확인이 아니다 — 2R #9). kill-switch 로 진입점을 끈 뒤 drain 이 끝나면 통과 |
| **V-28b** | N3 | `replay_policy` **allow 판정** 기록 | root 에 *정책 식별자+버전+판정* 이 남고 자식이 상속한다 |
| **V-28c** | N3 | `replay_policy` **deny 판정**(`order.created` 등) | **거부 이력이 남는다**. claim 에만 기록하는 구조로 되돌리면 deny 는 claim 에 도달하지 않아 **아무 기록도 없어** red (3R #5) |
| **V-30** | D3 | `record_kind IS NULL` 인 outbox 행(구버전 writer 흉내)을 fixture 로 넣고 poll | **도메인 경로로 발행된다**. 분기를 `record_kind = 'DOMAIN'` 로만 좁히면 그 행이 영원히 미발행으로 남아 red. expand 단계의 NULL 해석(D3)을 직접 관측하는 유일한 행 |
| **V-31** | C-5 | parity 대조기(P9-b) **self-test** — ① notification `outbox_events` 에서 컬럼 1개 제거 ② P8 신설 컬럼 1개에 `DEFAULT 'DOMAIN'` 부여 ③ 신설 컬럼 1개를 `NOT NULL` 로 ④ `OutboxEvent.java` 한 벌만 1바이트 변경 ⑤ `OutboxEventStatus` 를 order 판본으로 notification 에 복사 | 4종은 red, ⑤는 **green**(2집합 계약). 대조 축을 하나 빼면 해당 fixture 가 통과해 self-test 가 red. **정상 트리에서 lint 가 green 임도 함께 확인**한다 — 항상 red 인 lint 는 검사가 아니다 |
| **V-32** | D2 | notification `outbox_events` 에 **도메인 행 1건을 fixture 로 직접 INSERT** 후 poller 사이클 실행 (실제 DB + Kafka) | broker 에 해당 레코드가 도착하고 행이 `PUBLISHED` 로 전이. **poller 빈 배선을 지우면 red**. (replay 행은 진입점이 없어 이 PR 에서 만들 수 없으므로, 발행 표면이 실제로 도는지는 도메인 행으로 관측한다 — V-25 로 미루면 이 PR 이 "배선됐다" 수준의 판정으로 끝난다) |
| **V-33** | D6-4 | 원장 행 `publication_status='REQUESTED'` + 연결된 outbox 행을 **① `PUBLISHED` ② `FAILED` ③ `PENDING`** 세 상태로 두고 reconciler 실행 | ①→`PUBLISHED` ②→`PUBLISH_FAILED` ③→**전이 없음**(`REQUESTED` 유지). ③을 빼고 "REQUESTED 가 아니면 전이" 로 되돌리면 **발행 중인 건이 조기 종결**되어 red |
| **V-29** | N17 | **변이 목록 전수** — V-1~V-28b 가 지목한 각 변이 | 각 변이가 red → 복원 후 green. 변이 목록과 red 테스트 id 를 PR 본문에 **열거**한다. 자기대조 0건 |

**모듈별 그린 기준**: 각 PR 에서 `common` + 변경된 서비스 모듈 전체 테스트 0 실패 +
**`scripts/e2e/saga_e2e.py` 그린**(각 PR 이 `EXPECTED_MIGRATIONS` 를 함께 갱신하므로 이 게이트가 실제로 돈다).

---

## 7. 완료 조건

1. §1 의 **N1~N17 이 전부 거짓**임이 §6 의 V-1~V-29(V-13b·V-15b·V-21b·V-21c·V-21d·V-28b·V-28c 포함)로 확인된다.
   단 **N16 은 "런타임 DB 제약" 이 아니라 "코드 경로 강제" 로 축소 판정**한다 — `NOT NULL` contract 를 이번 범위에서
   제외했으므로(§10 R1), 팩토리를 우회한 직접 INSERT 는 DB 가 막지 못한다. 이 한계를 완료 보고에 명시한다
2. 4 PR 전부 머지되고, 각 PR 의 diff 리뷰가 **P1 = 0 이며 직전 라운드가 새 계약 표면을 추가하지 않았다**
   (수렴 판정 — 건수 추세로 종료하지 않는다)
3. `docs/runbooks/dlq-recovery.md` §6 이 **실행 가능한 절차**로 재작성되고 **§6-R 롤백 절차**를 포함하며,
   리허설이 **공개 진입점만** 사용한다 (직접 SQL 상태 변경 0 — ④-c-2a 재발 방지, P25 lint 로 강제)
4. `dead-letter-schema-parity-lint` 가 4 DB 신규 컬럼 parity 를 강제한다
5. `original_timestamp` NULL 비율 증적이 **실제 원장 집계**로 남는다(표본 0이면 "미측정" 으로 기록).
   비율이 높으면 **가용성 손실 수용 기준**을 ADR-0020 Update Log 에 기록한다

---

## 8. 영향 범위

| 모듈 | 변경 |
|---|---|
| `common` | `DlqOrigin`(+상관 4필드) · `DlqHeaders`(allowlist 판독) · `OriginalRecordReader`(신규) · `ReplayEligibility`+정책 레지스트리(신규) |
| `order-service` | 마이그레이션 **V8 · V9 · V10** · outbox 4파일 · deadletter 6파일 · reconciler |
| `product-service` | 마이그레이션 **V6 · V7 · V8** · 동일 |
| `payment-service` | 마이그레이션 **V6 · V7 · V8** · 동일 |
| `notification-service` | 마이그레이션 **V4 · V5 · V6** · **outbox 8파일 신설**(7파일 byte 동일 · `OutboxEventStatus` 는 payment 판본) · deadletter 6파일 · reconciler · yml `app.outbox.*` |
| `user-service` | **무변경** (Kafka consumer 없음) |
| scripts | `scripts/e2e/saga_e2e.py` `EXPECTED_MIGRATIONS` **3회 갱신**(2b-1 · 2b-2 · 2b-4) · lint 2종 + **`dead-letter-schema-parity-lint.sh` 에 `outbox_events` 축 확장**(P9-b) |
| docs | runbook §6 + §6-R · 05-data-design · 02-architecture · PHASE4 · grafana-alerts 주석 · **ADR-0012 D1 표 + Update Log**(P9-c, 2b-2 에서 선행) |

---

## 9. 배포 순서

1. **2b-1 배포** → 신규 컬럼 nullable, 기존 미결 집계 불변(V-18) 확인
2. **2b-2 배포** → 구버전 writer 소멸 확인 (`record_kind IS NULL` 신규 유입이 멈추는지)
3. **2b-3 배포** → 재실패 상관·재개방 경로 성립. **replay 는 아직 개시 불가**(진입점 없음)
4. **2b-4 배포 + backfill 실행** → NULL 잔여 0 확인 → 첫 replay 를 **리허설**로 수행하고 증적을 남긴다.
   `NOT NULL` contract 는 수행하지 않는다(§10 R1)

1~3 단계는 앞 단계만 배포된 상태에서 정지 가능하다. **4 단계 이후의 롤백은 P24 §6-R 절차를 따른다** —
replay 행이 남은 채 구버전으로 내려가면 안 된다.

---

## 10. 미해결 / 후속

| # | 항목 | 처분 |
|---|---|---|
| **R1** | `record_kind`·`root_record_id` 의 `NOT NULL` **contract 단계** | 이번 범위 밖. backfill 후 별도 마이그레이션 1개. 그때까지 **N16 은 코드 경로(팩토리·아키텍처 테스트)로만 강제**되고 DB 는 막지 않는다 — 완료 조건 1에 명시 |
| **R2** | 운영 클러스터(GKE) 실적용 — `ALTER_CONFIGS` 권한·실제 좌표 읽기 | ③ PR3d-b-2 GKE 세션에 합류 (④-c-2b-0 미충족 3과 동일 처분) |
| **R3** | **tombstone(payload NULL) 레코드의 replay** | 불가. `outbox_events.payload NOT NULL`(V4). 현재 tombstone 을 쓰는 토픽이 없으므로 스키마를 바꾸지 않는다. 필요해지면 컬럼 nullable 화 |
| **R4** | `replay_policy` 정책의 **장기 운영 정밀화** | 레지스트리·default-deny·기록/상속은 P19·P21 이 만든다. **초기 정책표(토픽별 allow/deny + 사전조건)는 2b-4 착수 전에 확정**하며, 최소 1개 토픽은 실제 도메인 상태 조회를 거친다(2R #7). 이연되는 것은 *운영 경험에 따른 정밀화*뿐이며 "전부 deny" 나 "검사 없는 allow" 로 시작하지 않는다 |
| **R5** | 소비 성공 확인 자동 종결 | ADR Alternative E 기각. replay 빈도가 오르면 재검토 |
| **R6** | `__consumer_offsets`·KRaft metadata bound, PVC 증설 | ADR §D4-2 후속 인프라 결정 |
| **R8** | `dead_letter_records.publication_status` **인덱스** | 추가하지 않는다 (C-7). 이 테이블은 DLQ 유입량에 유계이고 같은 컬럼을 스캔하는 `countUnresolvedByPublicationStatus`(2b-1)가 이미 무인덱스로 돈다. 4 DB 마이그레이션 + parity glob 확장 비용이 이득을 넘는다. **원장 행 수가 경보 `scan-limit`(100) 규모를 상시 넘기면 재검토** |
| **R7** | **outbox 행이 사라진 `REQUESTED` root 의 종결 경로** | 정상 경로에서는 cleanup 제외 조건이 부재를 만들지 않으므로 이 상태는 **계약 위반 신호**다. 자동 강등은 발행을 실패로 오분류하므로 채택하지 않았다(3R #3). 실제로 발생하면 **ADR-0020 Update Log 로 `PUBLISH_UNKNOWN` 축 추가를 결정**한다 — 상태값 신설은 ADR 사안이다 |

---

## 11. 정정 이력 — 계획 리뷰 3라운드 (2026-09-02, 40건 전량 반영)

### 11.1 1라운드 — 16건 전량 반영

초안이 **틀렸던 것**만 남긴다. 라운드별 증적은 `task-impl4-c2b-dlq-replay.audit.md`.

| 초안의 진술 | 무엇이 틀렸나 | 정정 |
|---|---|---|
| deadline = `min(ts, now + clockSkewBudget) + window` | ADR §D5-3 은 허용된 미래값을 **`now` 로 clamp** 하라고 명시한다. 초안 식은 4분 미래 timestamp 에 창을 4분 **연장**한다 | P19 — 거부 조건과 clamp 를 분리 |
| 2b-3 에서 진입점을 열고 2b-4 에서 상관을 붙인다 | 그 사이 구간의 재실패가 **독립 incident 로 갈라져** D5-4·D6-3 을 위반한다 | §4 — 상관(2b-3)을 진입점(2b-4)보다 **먼저** |
| 조건부 UPDATE 에 `outbox_event_id` 를 함께 넣는다 | auto-increment 라 **INSERT 후에만 존재**한다. outbox 를 먼저 만들면 경합 패자의 orphan 이 남는다 | P21 — claim → INSERT → id 기록 순서 |
| P15(현 P19)의 대조가 `last_replay_attempt_id` 를 읽는다 | **그 값을 쓰는 주체가 계획에 없었다**. 정상 재실패도 상관에 실패한다 | P21 claim UPDATE 가 attempt·group 을 함께 기록 |
| I-1 을 순차 검사로 강제 | resolve 가 NULL 을 읽은 뒤 replay 가 `REQUESTED` 를 커밋하면 terminal 이 덮인다 | P21 — 종결 UPDATE 에 `publication_status <> 'REQUESTED'` |
| 종결은 단일 엔티티 `resolve()` | ADR 은 **root + 활성 자식 원자 전파**와 자식 단독 종결 금지를 요구한다. 자식 id 처리도 없었다 | P5 신설 |
| purge 는 `COALESCE(discarded_at, resolved_at)` | `DISCARDED` → 재개방 → `RESOLVED` root 는 두 시각을 다 갖고, COALESCE 가 **과거 시각을 골라 조기 삭제**한다 | P4 — 상태별 `CASE` |
| backfill 을 "마이그레이션" 이라 적고 버전 미배정 | 앞 PR 마이그레이션은 이미 적용돼 수정 불가. 새 버전이 필요하다 | P23 — order V10 · product V8 · payment V8 · notification V6 |
| N16 "누락 INSERT 가 실패한다" + contract 이연 | 컬럼이 nullable 인 채로는 **DB INSERT 가 성공**한다. 완료 조건과 양립 불가 | 완료 조건 1 — N16 을 **코드 경로 강제**로 축소하고 한계 명시 |
| `replay_policy` 는 비워두고 첫 운영 사례에서 채운다 | 그러면 D5-4 의 늦은 이벤트 위험이 **아무 데서도 관리되지 않는다** | P19 — 레지스트리 + **default-deny** |
| D1 검증 = `save` 스텁 예외 1회 | poller 는 실패 후 **같은 객체를 다시 save** 한다(`:116`) — 두 번째가 성공하면 재발행이 없다. 초안 검증은 주장을 관측하지 못한다 | P13 — 두 save 모두 실패 + 실제 DB/broker 대조 |
| V-1~V-18 이 N1~N17 을 닫는다 | N1 의 topic·key·payload·eventId, N14, D8 소유 fence 에 **대응 행이 없었다**. "모든 기대값 변이" 는 목록이 없어 공백을 못 메운다 | §6 — V-1~V-29 로 확장, 변이 목록 열거 의무화 |
| NULL 비율을 통합테스트 fixture 로 산출 | ADR 이 미측정이라 한 것은 **실제 원장 분포**다. fixture 를 세는 것은 자기대조 | P22 — 실제 원장 read-only 집계, 표본 0 이면 "미측정" |
| E2E 게이트 무영향 | `saga_e2e.py:64-68` 이 **버전 집합 정확 일치**를 강제한다. 갱신 없으면 readiness 가 먼저 red | P7·P8·P23 — 각 PR 이 `EXPECTED_MIGRATIONS` 동반 갱신 |
| "되돌리기 = 앞 단계에서 정지 가능" | replay 행이 생긴 뒤 롤백하면 구 poller 가 `__replay__` 를 토픽으로 쓴다 | P24 §6-R 롤백 절차 신설 |
| notification `app.outbox.*` 위치 미명시 | ADR-0007 상 런타임 정책은 base 소유이고 프로파일 배치가 금지된다 | P9 — base `application.yml` 소유 명시 + 바인딩 테스트 |
### 11.2 2라운드 — 14건 전량 반영 (1R 수정이 만든 새 결함 8건 포함)

| 1R 수정이 만든 진술 | 무엇이 틀렸나 | 정정 |
|---|---|---|
| 종결 UPDATE 에 `AND publication_status <> 'REQUESTED'` | SQL 에서 `NULL <> 'REQUESTED'` 는 **UNKNOWN** — `publication_status IS NULL` 인 **절대다수 행이 종결 불가**가 된다. ADR §D6-2b 표는 NULL 에서 종결을 허용한다 | P21 — `(IS NULL OR <> 'REQUESTED')` + 네 상태 전수 테스트(V-13) |
| claim 조건에 publication 축만 검사 | resolve 가 먼저 커밋하면 replay 가 그 뒤에 `REQUESTED` 를 기록해 **terminal + REQUESTED** 조합이 생긴다. 1R 은 replay 선점 순서만 막았다 | P21 — `AND status IN ('OPEN','ACKED')` + 양방향 latch 테스트(V-14) |
| deadline 식과 상속만 정의 | ADR §D5-3 의 **`now < replay_deadline` 강제**가 작업·검증 어디에도 없었다 — 만료 사건을 발행해도 green | P19 4항 + V-13b 경계 3종 |
| `record_kind=REPLAY` 를 "attempt 기록 존재" 로 치환 | **동치가 아니다** — 유효 attempt 가 살아 있는 동안 같은 헤더를 붙인 도메인 레코드가 owner/group/topic 대조를 통과한다 | P14 — root 의 durable fingerprint(`event_id`·`original_key`·`original_timestamp`·`origin_topic`) 대조 + 음성 8종(V-19) |
| purge = 조회 후 자식 동반 삭제 | 조회와 삭제 사이에 P15 가 재개방하면 **살아 있는 incident 와 새 자식을 지운다**. P5·P15 만 root 잠금으로 직렬화되고 purge 는 규약 밖이었다 | P4 — purge 도 `FOR UPDATE` + 상태 재검사 (V-21c) |
| reconciler 가 outbox 상태를 나중에 조회 | cleanup 이 `PUBLISHED` outbox 를 무조건 지운다(`OutboxEventJpaRepository:31-33`). reconciler 가 retention 이상 멈추면 **root 가 `REQUESTED` 에 영구 고착**되고 I-1 때문에 종결도 못 한다 | P12 — cleanup 제외 조건 + outbox 부재 시 `PUBLISH_FAILED` 강등 (V-21b) |
| `replay_policy` 초기값을 "보수적으로 deny 쪽" (R4) | 전부 deny 면 V-25·첫 리허설이 불가능하고, 검사 없는 allow 면 D5-4 계약 미구현이다. 게다가 **P1 이 만든 컬럼을 아무도 쓰지 않았다** | P19·P21 — 초기 정책표 확정 + 판정 기록·상속 (V-28b), R4 재작성 |
| V-1 = partition 무시 변이 | replay 는 **같은 key 를 보존**하므로 기본 partitioner 가 원본과 같은 파티션을 골라 **변이가 검출되지 않는다** | V-1 — fixture 를 기본 hash 파티션과 다른 파티션에 기록 |
| 롤백 절차를 runbook 에만 기재 | runbook 은 **프로세스 기동을 막지 못한다** — V-28 이 문서 확인이라 false-green | P24 — kill-switch(`app.dead-letter.replay.enabled`) + **배포 preflight 게이트**, V-28 은 배포 명령 실패를 확인 |
| V-6 = payload 안 eventId 변이 | payload bytes 도 함께 바뀌어 **V-5 가 먼저 거부**한다. eventId 대조를 제거해도 green | V-6 — 원장 `event_id` 만 다른 fixture |
| 재개방 + Slack 알림을 "같은 트랜잭션" | 외부 webhook 은 rollback 대상이 아니다. 기존 계약(`DeadLetterRecorder:20-23`)이 이미 **0회 가능**을 명시한다 | P15 — commit 후 best-effort 로 완화 |
| V-20 = 타 서비스 원장 행 id 로 요청 | endpoint 는 숫자 id 만 받고 **자기 datasource 만** 조회한다 — 그 입력이 표현되지 않는다 | V-20 — 아키텍처 테스트로 전환 |
| notification outbox 복제 | `OutboxEventCleanupScheduler:23`·`OutboxRetentionProperties:18-19` 의 "notification 제외" javadoc 이 **머지 즉시 거짓**이 된다 | P9 — 같은 PR 에서 javadoc 계약 갱신 |
| 재번호 후 상호참조 | `P4-4` 는 이 문서에 없는 항목(ADR-0020 계획서의 것)이었고, 부모 범위에서 **P13 이 누락**됐다 | 머리말·N17 주석 교정 |

> **2R 이 확인해 준 것**: 2b-3(상관) 선배포 순서는 성립한다 — attempt 기록이 아직 없는 상태는 **정상적인 불일치**로
> 처리되어 독립 행이 될 뿐이다. P5(전파)와 P15(재개방)도 root 잠금으로 직렬화되어 무한 전이가 생기지 않는다.

### 11.3 3라운드 — 10건 전량 반영 (2R 수정이 만든 새 결함 6건 포함)

| 2R 수정이 만든 진술 | 무엇이 틀렸나 | 정정 |
|---|---|---|
| claim 은 root 에 `publication_status` 를 건다 | 첫 replay 성공 후 root 가 `PUBLISHED` 가 되는데 claim 은 `NULL`/`PUBLISH_FAILED` 만 허용한다 — **두 번째 replay 가 구조적으로 불가능**했다. V-16 의 "3회 replay" 가 성립하지 않았다 | P21 — **target row(최신 활성 행) ↔ 상관 앵커(root) 분리**, 잠금 순서 root→child, I-1 을 incident 단위로 |
| deadline 을 "root 에서 1회 계산하고 상속" | **어디에 영속하는지 정의가 없었다**. 매 요청 transient 계산이나 fixture 직접 주입으로도 V-11·V-13b 가 green | P21 2b — `replay_deadline=COALESCE(replay_deadline, :calculated)` + 자식 복사, V-11 을 두 단언으로 분리 |
| outbox 부재 시 `PUBLISH_FAILED` 로 강등 | 행 부재는 **실패의 증거가 아니다**. 이미 발행된 행이 사라져도 같은 관측이라 **발행을 실패로 감사 기록하고 재요청까지 연다**. ADR §D6-4 는 실제 `FAILED` 소진에만 그 전이를 준다 | P12 — 강등 철회, fail-closed 경보 + 운영자 판정, 종결 경로는 §10 **R7**. V-21b/V-21d 분리 |
| fingerprint 대조로 `record_kind` 를 대체 | **P15 의 실제 대조 목록에 그 4값이 없었고**, ADR 계약 변경을 어디에도 기록하지 않았다 | P15 — 4값 명시(+`original_key` null-safe) · P24 — **ADR-0020 Update Log 기재** |
| `replay_policy` 초기 정책표는 "2b-4 착수 전 확정" | 계획이 정책 축을 **비워 둔 채** 완료 판정으로 간다. 게다가 deny 는 P19 에서 걸러져 claim 에 도달하지 않으므로 **거부 이력이 남지 않았다** | P19 — **10토픽 초기 정책표를 이 문서에 확정** + allow/deny **양쪽** 감사 write. V-28c 추가 |
| kill-switch 가 "설정 1개로 즉시" 차단 | Spring 정적 설정은 **재기동 없이 바뀌지 않는다**. preflight 도 대상 배포 명령이 불명확했다(`rollout-convergence-gate.sh` 는 **배포 후** 게이트) | P24 — 재기동 포함 3단계 절차 명시 + `scripts/replay-drain-preflight.sh` 신설(이미지 capability 판별·4 DB·fail-closed) |
| V-2 = timestamp 미탑재 → deadline 연장 관측 | P19 가 자식 재계산을 금지해 **상속되므로 timestamp 를 빼도 deadline 이 늘지 않는다** — 변이 미검출 | V-2 — 발행 레코드 timestamp 직접 비교 |
| V-6 = eventId fence 통과 시 멱등 억제가 깨짐 | reader 가 읽은 **원본 payload** 를 그대로 발행하므로 소비자가 보는 eventId 는 원본 그대로다 — 그 관측은 일어나지 않는다 | V-6 — "요청 승인 + outbox 행 생성" 으로 red 판정 |
| V-15 = 재개방 시 "알림 1회" | P15 를 best-effort 로 완화해 놓고 검증은 **정확히 1회**를 요구했다 — 계약이 둘이 됐다 | V-15 = commit 후 1회 시도 · **V-15b** = 알림 0회 허용 |
| §11 이 audit 파일을 인용 | 그 파일이 **저장소에 없었다** | `task-impl4-c2b-dlq-replay.audit.md` 생성 |

> **3R 이 확인해 준 것**: P5·P15·purge 는 전부 canonical root 를 먼저 잠그므로 **잠금 순환이 없다**.
> 다만 3R #1 이 target child 를 도입했으므로 **root → target child 순서를 명문화**했다(P21).

---

## 부록 A — 범위 분리 이력 (2026-08 ~ 09)

### A.1 왜 분리됐나

④-c-2 계획 리뷰 3라운드에서 결함 10건 중 **6건이 replay 경로**에 몰렸고(#1·#2·#3·#4·#5·#7),
그 중 다수가 **계획 리뷰만으로 닫히지 않는 결정 사안**이었다.

**같은 주장이 두 라운드 연속 반증됐다** — "재발행 중복 0" 을:
- 2R: `REPLAY_REQUESTED → 발행 → REPLAY_PUBLISHED` 2단 상태머신으로 시도 → **발행 직후 사망 시 발행 여부 판별 불가**로 반증
- 3R: 기존 `outbox_events` 재사용으로 시도 → **`OutboxPollingService:83-86` 이 broker ack 후 별도로 `PUBLISHED` 를 저장**하므로 같은 crash window 존재

두 번 반증된 표면은 계획서 수정이 아니라 **설계 결정**이 필요했다 → [ADR-0020](../adr/0020-dlq-replay-contract.md).

### A.2 ADR 이 확정해야 했던 것 (D1~D7) — **결정 당시의 문제 진술로 보존**

| 항목 | 문제 | ADR-0020 대응 |
|---|---|---|
| **D1** 재발행 보장 수준 | 일반 DB outbox 로는 exactly-once 발행이 불가능하다 | §D1 — publication at-least-once + 소비 효과 멱등 |
| **D2** notification outbox | notification 에는 outbox 가 없고 ADR-0012 D1 이 `processed_events` 만 할당했다 | §D2 — 신설(ADR-0012 D1 개정) |
| **D3** replay 레코드 표현 | `OutboxEvent` 는 토픽·partition·임의 key·헤더를 표현하지 못한다 | §D3 — additive 컬럼 + poller kind 분기 |
| **D4** replay 원본 출처 | 좌표를 읽는 컴포넌트가 없고, 브로커 retention 이 선언돼 있지 않았다 | §D4·§D5-1 — 계약 선언(④-c-2b-0) + 좌표 검증 |
| **D5** 금지 정책 근거 | `payload_truncated` 를 금지 사유로 쓰는 것은 자기모순이다 | §D5-2 — 독립 6축 |
| **D6** 종결 실행자 | 원장↔outbox 를 잇는 것이 없고 종결 주체가 미정이었다 | §D6 — 두 축 물리 분리 + 사람만 종결 |
| **D7** 운영 진입점 | CLI vs Actuator 미정 | §D7 — `deadletter` 엔드포인트 확장 1종 |
| **D8** (계획 검증 중 발견) | 소비 경로 원장의 `origin_topic` 은 정의상 남의 토픽이라 `1 topic = 1 producer` 를 넘는다 | §D8 — 명시적 예외 + fence |

### A.3 착수 조건 — **전부 종료 (2026-09-02)**

1. D1~D7 ADR 확정 → ✅ [ADR-0020](../adr/0020-dlq-replay-contract.md) ([#98](https://github.com/Kimgyuilli/PeakCart/pull/98))
2. 브로커 retention 실설정 → ✅ ④-c-2b-0 ([#99](https://github.com/Kimgyuilli/PeakCart/pull/99))
3. ④-c-2a 머지 → ✅ ([#90](https://github.com/Kimgyuilli/PeakCart/pull/90))
4. `/plan task-impl4-c2b-dlq-replay` 재실행 → ✅ 이 문서 (§1~§10)

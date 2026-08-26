# ④-c-2b — DLQ replay 경로 (범위 정의 · **ADR 선행 필요**)

> 형제: **④-c-2a (원장 적재)** — `task-impl4-c2a-dlq-ledger.md`
> 부모: `docs/plans/task-impl4-choreography-saga.md` P9·P10
> 상태: **🔲 계획 미착수** — 아래 §2 의 결정 사항이 ADR 로 확정된 뒤 `/plan` 을 다시 돌린다
> 근거: ④-c-2a 계획 리뷰 3라운드 audit — `task-impl4-c2a-dlq-ledger.audit.md`

---

## 1. 왜 분리됐나

④-c-2 계획 리뷰 3라운드에서 결함 10건 중 **6건이 replay 경로**에 몰렸고(#1·#2·#3·#4·#5·#7), 그 중 다수가 **계획 리뷰만으로 닫히지 않는 결정 사안**이었다.

**같은 주장이 두 라운드 연속 반증됐다** — "재발행 중복 0" 을:
- 2R: `REPLAY_REQUESTED → 발행 → REPLAY_PUBLISHED` 2단 상태머신으로 시도 → **발행 직후 사망 시 발행 여부 판별 불가**로 반증
- 3R: 기존 `outbox_events` 재사용으로 시도 → **`OutboxPollingService:83-86` 이 broker ack 후 별도로 `PUBLISHED` 를 저장**하므로 같은 crash window 존재. ADR-0018 자체가 outbox 를 at-least-once 로 규정

두 번 반증된 표면은 계획서 수정이 아니라 **설계 결정**이 필요하다.

---

## 2. ADR 이 확정해야 할 것

### D1. 재발행 보장 수준
- 일반 DB outbox 로는 **exactly-once 발행이 불가능**하다. `send().get()` 후 상태 저장 사이의 crash window 는 구조적이다.
- 선택지: ① 보장을 **"발행 at-least-once + 동일 `eventId` 와 `processed_events` 로 소비 효과 멱등"** 으로 규정 / ② Kafka 트랜잭션 도입
- ①이면 §검증에서 "중복 발행 0" 을 삭제하고 **broker ack 직후 DB 저장 실패를 주입해 실제 중복 발행 + 소비 효과 1회**를 검증해야 한다

### D2. `notification-service` 의 outbox
- **notification 에는 outbox 가 없다** — `global/outbox/` 부재, `outbox_events` 마이그레이션 부재, `application.yml:58` "소비 전용(outbox 미소유)", **ADR-0012 D1 이 Notification DB 에 `processed_events` 만 두도록 규정**
- 선택지: ① notification 에 outbox 신설 → **ADR-0012 D1 개정** + Flyway + 롤링 배포 범위 갱신 / ② replay 지휘를 다른 서비스에 위임 → **DB 간 단일 트랜잭션 보장 철회** + 별도 durable command 전달·fence 설계

### D3. replay 전용 레코드 표현
현 `OutboxEvent` 는 replay 충실도를 표현하지 못한다:
```java
new ProducerRecord<>(event.getEventType(), null, event.getAggregateId(), event.getPayload())
//                                          ↑ partition 고정 null
```
- key 는 `aggregateId`(String, 50자 상한) · 헤더는 trace/user 뿐 · partition 지정 불가
- 필요한 것: record kind · destination topic · **nullable 임의 key** · **destination partition** · 원본 application-header allowlist · raw payload. poller 가 replay kind 를 분기해 `ProducerRecord(topic, partition, key, payload, headers)` 를 조립해야 한다
- 도메인 outbox 의 `event_id` 와 replay 대상 payload 의 동일 `eventId` 를 구분할 **추적키**도 필요하다

### D4. replay 원본을 어디서 읽나
- 2R 은 "원장 payload = 진단용(마스킹 허용) / replay 원본 = 원본 토픽의 기록된 좌표" 로 역할을 갈랐다. **그런데 그 좌표를 읽는 컴포넌트가 저장소에 없다.**
- 필요한 것: `OriginalRecordReader`(assign → seek → poll, **반환 offset 이 요청 offset 과 같은지 검증**) · 소유 서비스 · consumer 설정 · 조회 실패 처분
- **전제 미충족**: `app.idempotency.floor.kafka-topic-retention=7d` 는 **멱등 보존 가드용 선언값**일 뿐이고, 실제 토픽에 `retention.ms`·`cleanup.policy` 가 **어디에도 설정돼 있지 않다**(`NewTopic` 선언 · docker-compose · k8s 전부). `retention.bytes`·토픽 재설정·compaction hole 이 있으면 7일 이내라도 원문이 없을 수 있다
  → **AdminClient 로 runtime `cleanup.policy`·`retention.ms`·`retention.bytes`·beginning/end offset 을 검사**하고, 좌표 부재·compaction hole 은 replay 불가로 종결해야 한다
  → 브로커 retention 을 **실제로 설정하는 것**이 D4 의 선결 조건이다

### D5. replay 금지 정책의 근거
- 2R 은 "마스킹·절단된 원장 행은 replay 불가" 로 정했으나, **replay 원본이 좌표라면 진단 사본의 변형 여부는 충실도와 무관하다**(3R #5 — 내 수정이 만든 자기모순)
- 민감 이벤트 자체의 재발행을 막으려는 정책이라면 `payloadTruncated` 가 아니라 **별도 `replayPolicy` / 금지 사유**를 토픽·이벤트 유형별로 정의해야 한다

### D6. 종결 실행자
- `REPLAY_REQUESTED` 이후 누가 `RESOLVED` 로 닫나. 기존 poller 는 `OutboxEvent` 만 갱신하고 **`DeadLetterRecord` 를 모른다**. outbox 가 `FAILED` 가 된 경우의 원장 처분도 없다
- 필요한 것: 원장↔outbox 를 잇는 `outbox_event_id` · 종결 주체(관리 API 가 `PUBLISHED` 확인 후 전이 / reconciler 가 조회 후 전이) · `FAILED` 재요청·`DISCARDED` 규칙·권한
- **리허설은 공개된 운영 진입점만 사용하고 직접 SQL 상태 변경을 금지**해야 한다 (금지하지 않으면 P10 리허설이 false-green)

### D7. replay 운영 진입점
- 운영 CLI vs Actuator 관리 엔드포인트 — 인증·트랜잭션 경계 포함해 1종 확정
- **자동 재발행은 영구 금지**(부모 §2.3-B, `docs/04-design-deep-dive.md:445-466`) — 진입점은 사람이 개시한다

---

## 3. ④-c-2a 가 남겨둔 확장 지점

2a 는 2b 를 막지 않도록 설계한다:
- 원장 상태 컬럼을 `VARCHAR` + 애플리케이션 검증으로 두어 `REPLAY_*`/`RESOLVED` 를 **additive 로 추가**할 수 있게 한다
- replay 관련 컬럼(`outbox_event_id`·`replayedAt`·`replayedBy`·`replayEventId`·`replayPolicy`)은 2b 의 마이그레이션이 추가한다
- 2a runbook 은 **"replay 는 ④-c-2b 이전까지 불가"** 를 명시한다

---

## 4. 착수 조건

1. §2 의 D1~D7 을 ADR 로 확정 (D2 는 **ADR-0012 D1 개정** 여부를 포함)
2. 브로커 retention/compaction 을 **실제로 설정**하고 그 값을 계약으로 고정 (D4 선결)
3. ④-c-2a 머지 완료
4. 그 후 `/plan task-impl4-c2b-dlq-replay` 재실행

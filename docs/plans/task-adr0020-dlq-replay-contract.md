# task-adr0020-dlq-replay-contract — DLQ replay 계약 ADR-0020

> 작성: 2026-09-01 · **개정: 2026-09-01 (계획 리뷰 1R 13건 전량 반영)**
> 관련 Phase: Phase 4 (MSA 분리) — 구현 ④ Choreography Saga
> 선행: ADR-0011(모듈 경계·NewTopic 프로비저닝 소유), ADR-0012(D1 DB 경계·D4 토픽×producer 매트릭스·D5 retention), ADR-0018(보상/환불 계약 — outbox at-least-once)
> 입력: `docs/plans/task-impl4-c2b-dlq-replay.md` §2 (D1~D7) · `docs/plans/task-impl4-c2a-dlq-ledger.audit.md` · `docs/runbooks/dlq-recovery.md` §6
> 후속: 구현 **④-c-2b**(replay 경로 구현 — 본 ADR 확정 후 `/plan task-impl4-c2b-dlq-replay` 재실행)
> 관련 ADR: 신규 = **ADR-0020** (Proposed → Accepted) · **ADR-0012 D1/D4 관계 판정**(무효화 여부는 P2·P3 채택안에 종속 — §2.2-11) · **ADR-0018 producer 규약 관계 판정** · ADR-0011 은 관계 설명만(§2.2-6)

---

## 1. 명제 (부정형 — 무엇이 성립하면 미완인가)

다음 중 **하나라도 성립하면 이 task 는 미완**이다:

- **N1.** `task-impl4-c2b-dlq-replay.md` §2 의 D1~D7 중 어느 하나라도 ADR-0020 에서 **선택지 비교 없이 결론만** 적혀 있거나 미결로 남아 있다.
- **N2.** ADR-0020 이 규정한 재발행 보장 수준이 **"중복 발행 0" 을 다시 주장**한다. (2R·3R 에서 두 번 반증된 주장이다.)
- **N3.** replay 발행이 **원본 토픽의 producer 가 아닌 서비스**에서 일어나는데, **ADR-0012 D4 매트릭스의 producer 컬럼**(= `1 topic = 1 producer` 의 실제 SSOT, §2.2-6)과의 관계를 명시적으로 처분하지 않았다.
- **N4.** replay 가 **공유 토픽의 모든 consumer group 에 재전달**된다는 사실과, 그 무해성의 근거·한계(§2.2-4·§2.2-9)를 계약으로 정의하지 않았다.
- **N5.** 브로커 `retention.ms`·`cleanup.policy`·`retention.bytes` 가 선언되지 않은 채 남아 있거나, 선언은 했는데 **기존 토픽에 실제로 적용되는 경로**가 없다.
- **N6.** replay **금지 축이 서로 독립 조건으로 분리되지 않았다** — `eventId` 부재 · `failed_consumer_group = '__unknown__'` · `origin_kind = DLQ_ORIGIN` · 좌표 무효 · 정책 금지. (특히 `payloadTruncated` 를 금지 사유로 쓰면 위반이다.)
- **N7.** ADR-0012 D1(Notification = `processed_events` 만) 또는 D4(producer 컬럼)를 바꾸는데 그 처분이 ADR Status 에 반영되지 않았거나, **기존 superseder(ADR-0016)를 덮어써서 무효화 이력이 소실**된다(§2.2-8).
- **N8.** **실행 표면**(코드·설정·운영 주장)의 검증 항목 중 **실패 주입 없이 "존재한다"·"관측했다"로만 판정되는 것**이 남아 있다. (P4-4 의 초안이 정확히 이 결함이었다 — §정정 이력 ⑧) — *문서 결정 항목은 실패 주입 대상이 아니라 **선택지/채택/근거/Consequences 구조 검토** 대상이다. 둘을 같은 잣대로 요구하면 §5 가 자기모순이 된다(2R #7).*
- **N9.** **broker ack 만으로 사건이 종결**되게 정의돼 있다. 재발행 성공은 실패했던 consumer 의 업무 처리 성공을 증명하지 않는다(§2.2-7). — *상태 이름을 `RESOLVED` 에서 `REPLAY_PUBLISHED` 로 바꾸는 것으로는 회피되지 않는다. ack 상태를 **terminal 로 삼거나 unresolved 집계에서 빼면** 같은 조기 종결이다(2R #1).*
- **N10.** `REPLAY_*` 상태를 추가하면서, **미결 backlog·age 경보·purge 쿼리가 그 상태를 어떻게 취급하는지**를 정하지 않았다. 현 쿼리는 상태 집합을 리터럴로 박고 있어(§2.1-p) 새 상태가 **조용히 관측에서 사라지고 영원히 정리되지 않는다**.
- **N11.** **재발행은 성공했으나 업무 consumer 가 다시 실패한 경로**가 원래 원장 행과 연결되지 않는다(§2.2-12). 그 경로는 새 좌표·같은 group 으로 **별도 원장 행**을 만들고, 원래 행은 발행 성공 상태에 남는다 — 이중 원장이자 조기 종결이다.
- **N12.** `replay_deadline` 의 **기준시각 출처와 계산식**이 결정되지 않았다(§2.2-13). "원장 `occurredAt` 기준이든 원본 발생시각 기준이든 아무거나" 는 결정이 아니다.

---

## 2. 배경 / 제약

### 2.1 착수 전 코드 검증 (2026-09-01) — ADR 문구가 아니라 현재 코드 기준

| # | 검증 대상 | 결과 |
|---|---|---|
| a | outbox 발행의 crash window | **확인**. `order-service/.../OutboxPollingService.java:83` 이 `kafkaTemplate.send(...).get(timeout)` 으로 **broker ack 를 받고**, `:85-86` 이 `event.markPublished(); outboxEventRepository.save(event)` 를 **별도로** 실행한다. 그 사이 사망하면 `PENDING` 인 채 재발행된다. product/payment 동일 |
| b | `OutboxEvent` 의 표현력 | **replay 를 표현하지 못한다**. 컬럼 = `aggregate_type(50)·aggregate_id(50)·event_type(50)·event_id(36,unique)·payload(TEXT)·status·retry_count·last_attempted_at·created_at·published_at·trace_id·user_id`. `buildRecord()`(`:119-125`) = `new ProducerRecord<>(eventType, null, aggregateId, payload)` — **토픽=eventType 고정 · partition=null 고정 · key=aggregateId(NOT NULL, 50자)**, 헤더는 trace/user 둘뿐 |
| c | notification 의 outbox | **없다**. `notification-service/.../global/` = `config·deadletter·idempotency`. 마이그레이션 `V1·V2·V3` 에 `outbox_events` 부재. ADR-0012 D1 표(`0012:47`)가 Notification 에 `processed_events` 만 할당 |
| d | 좌표 reader | **없다**. `assign(`·`.seek(` 를 쓰는 production 코드 0건 |
| e | `AdminClient`/`KafkaAdmin` 직접 사용 | **0건**. 토픽은 `NewTopic` 빈으로만 선언, Spring Boot 자동설정 `KafkaAdmin` 이 생성 |
| f | 토픽 config 선언 | **0건**. `retention.ms`·`cleanup.policy`·`retention.bytes` 를 java/yml/yaml/sh 전역 grep — 히트 0. `TopicBuilder.name(...).partitions(n).replicas(1).build()` 만 있고 `.config(...)` 없음 |
| g | 브로커 레벨 설정 | **없다**. `docker-compose.yml`·`k8s/base/infra/kafka/kafka.yml` 에 `KAFKA_LOG_RETENTION_*` 부재 |
| h | **실효 retention** (초안 §2 정정) | 초안은 "설정돼 있지 않다"고만 적었다. **실효값은 미정의가 아니라 Apache 기본값**이다 — `apache/kafka:3.8.1` 이미지의 `/opt/kafka/config/server.properties:105` = `log.retention.hours=168`(7d), `log.retention.bytes` **주석 처리**(=-1), `cleanup.policy` 기본 `delete`. **오늘도 사실상 7일 보존이며, 문제는 값이 아니라 그것이 계약이 아니라는 것** |
| i | `KafkaAdmin` 이 기존 토픽 config 를 고치는가 | **기본은 안 고친다**. `spring-kafka-3.3.14` 바이트코드에 `private boolean modifyTopicConfigs;`(초기화 없음 = false) + `setModifyTopicConfigs(boolean)`, `createOrModifyTopics` 가 그 플래그로 분기(`ifeq`). Spring Boot 키 `spring.kafka.admin.modify-topic-configs` 가 `spring-configuration-metadata.json` 에 존재 → **선언만 추가하면 신규 토픽에만 붙는다** |
| j | 원장 상태 집합 | `DeadLetterStatus` = `OPEN·ACKED·DISCARDED`. DB 는 `status VARCHAR(30)` + 애플리케이션 검증이라 **`REPLAY_*`/`RESOLVED` 를 마이그레이션 없이 additive 추가 가능**(2a 가 남긴 확장점) |
| k | 원장 식별자 | `dead_letter_records` = `cluster_id·topic_generation·origin_topic·origin_partition·origin_offset·failed_consumer_group`(6컬럼 NOT NULL) + `origin_kind·event_id(nullable,36)·original_key·payload·payload_truncated·attempt_count`·감사필드. replay 컬럼 없음 |
| l | 운영 진입점 | `DeadLetterEndpoint` = `@Endpoint(id="deadletter")` · `@ReadOperation backlog()` · `@WriteOperation transition(@Selector id, action, actor, reason)`. **2a 가 이미 write 진입점을 만들어 뒀다** |
| m | 멱등 창 선언 | `app.idempotency.floor.{kafka-topic-retention:7d, dlq-replay-window, max-consumer-downtime, backfill-replay-window}` 가 4서비스 yml 에 있고 `IdempotencyRetentionProperties.isRetentionAtLeastFloor()` 가 fail-fast. **브로커에는 아무것도 설정하지 않는다** |
| n | 공유 DLQ 토폴로지 | `DlqTopology.CONSUMPTION` — `payment.completed`·`payment.failed`·`payment.refunded` 를 order·product·notification **3서비스**가, `order.created`·`order.cancelled` 를 product·payment·notification **3서비스**가 각자 group 으로 소비 |
| **o** | **quarantine 의 실제 조건** (1R #3 — **내 추론 반증**) | quarantine 은 `failed_consumer_group == DlqOrigin.UNKNOWN_CONSUMER_GROUP("__unknown__")` 이다(`DlqOrigin:41-63`, `DlqTopology.ownsQuarantine`). **`eventId` 와 무관하다** — `DeadLetterRecorder:57` 이 `DlqPayloads.extractEventId(objectMapper, payload)` 로 group 과 **독립적으로** 추출한다. 좌표 출처는 또 다른 축이다(`DlqOriginKind.RESOLVED_ORIGIN` = 원본 토픽 좌표 / `DLQ_ORIGIN` = **`.dlq` 자신의 좌표**). → **세 축이 서로 독립**이다 |
| **p** | **원장 조회·정리 쿼리의 상태 집합** (1R #5) | `DeadLetterRecordJpaRepository:77-95` 가 `countUnresolved()`·`findOldestUnresolvedOccurredAt()`·`findStaleUnresolved()` 에 **`status IN ('OPEN','ACKED')` 를 JPQL 리터럴로** 박았고, purge 대상은 **`status = 'DISCARDED'`** 뿐이다. `DeadLetterMetrics`·`DeadLetterMaintenanceScheduler` 가 같은 쿼리를 쓴다 → 새 상태는 **미결 집계에서도 purge 대상에서도 동시에 빠진다** |
| **q** | `DeadLetterRecord` 의 동시성 제어 | **`@Version` 없음**. 같은 원장 행에 replay 요청이 동시에 오면 outbox 행이 2개 생길 수 있다 |
| **r** | `processed_events` 정리 기준 | `ProcessedEventCleanupScheduler:41` = `LocalDateTime.now().minus(properties.getRetention())` — **각 서비스 로컬 시각 기준**의 무조건 삭제다. 좌표가 남아 있어도 **다른 group 의 멱등 행은 이미 지워졌을 수 있다** |
| **s** | Kafka 저장 용량 | `k8s/base/infra/kafka/kafka.yml:1-11` — `kafka-pvc` requests **1Gi**, `replicas(1)`. "7일 보존" 을 계약으로 선언하면 **디스크가 그 계약을 지킬 수 있는지**가 따라온다 |
| **t** | `1 topic = 1 producer` 의 실제 SSOT (1R #4 — **내 지목 오류**) | ADR-0011 D2(`:71`)는 **"`config.KafkaConfig` 토픽/DLQ 빈 = 발행 서비스 전속"** — 즉 **NewTopic 프로비저닝 소유**다. **발행 권한**을 고정하는 것은 **ADR-0012 D4 매트릭스의 producer 컬럼**(`0012:76-84`)과 ADR-0018 이다 |
| **u** | 기존 무효화 이력 (1R #12) | `docs/adr/README.md` — **ADR-0011 은 이미 `Partially Superseded`(ADR-0014 에 의해)**, **ADR-0012 도 이미 `Partially Superseded`(ADR-0016 에 의해)**. ADR-0020 을 단일 superseder 로 덮어쓰면 기존 이력이 사라진다 |
| **v** | Kafka 를 쓰는 서비스 수 (2R #5) | **4다, 5가 아니다**. `UserApplication:20` = `@SpringBootApplication(exclude = KafkaAutoConfiguration.class)` — User 는 이벤트 발행/소비를 하지 않고 Kafka 인프라 빈이 0개다. `isolation.level` 같은 consumer 설정의 전파 대상은 order·product·payment·notification **4서비스** |
| **w** | 빌드 대상 모듈 수 (2R #8) | **10이다, 8이 아니다**. `settings.gradle:6-22` = `common` · `peekcart-common-observability` · `peekcart-common-auth` · 5서비스 · `gateway` · `internal-token-contract`. "8모듈 그린" 같은 숫자 게이트는 **두 모듈을 빠뜨려도 통과**한다 |
| **x** | `dead_letter_records` 의 마이그레이션 소재 (2R #3) | **4개 DB 전부**다 — `order V6` · `product V5` · `payment V5` · `notification V3`. 따라서 **원장 스키마 변경은 P2 채택안과 무관하게 항상 4 DB** 다(outbox 변경은 3 DB, notification outbox 채택 시 4 DB) |
| **y** | 멱등 이력의 기준시각 (2R #4) | `ProcessedEvent.create():46` = `processedAt = LocalDateTime.now()` — **각 consumer 서비스의 로컬 시각**. 삭제도 `ProcessedEventCleanupScheduler:41` 의 로컬 `now - retention`. 다른 group 의 성공 시각은 **분리된 DB 안**에 있어 조회 불가하고, `DeadLetterRecord.originalTimestamp` 는 **nullable**(`:83-84`) |
| **z** | 파티션 수 | 업무 토픽 `partitions(3)` · `.dlq` `partitions(1)` (`OrderKafkaConfig:39-68` 외). `replicas(1)`. 용량 하한은 **파티션별 `retention.bytes` × 파티션 수** 로 합산해야 하며 토픽 수만으로는 안 나온다 |

### 2.2 검증이 만든 범위 변화 / 결정 표면

1. **D4 의 서술이 절반 틀렸다 (→ P4).** "retention 이 설정돼 있지 않다"는 **선언 기준 참, 실효 기준 거짓**(§2.1-h). 실효 7일이 이미 성립하므로 P4 는 *동작을 바꾸는 작업이 아니라 계약을 명문화하는 작업*이다. 이 구분을 흐리면 "설정했다" 는 완료 보고가 **아무 것도 바뀌지 않았는데 성공처럼 보이는** false-green 이 된다.
2. **`modify-topic-configs` 없이는 P4 가 신규 클러스터에서만 참이다 (→ P4).** 토픽은 이미 존재한다(compose·e2e·GKE). `NewTopic.config()` 만으로는 기존 토픽에 반영되지 않는다(§2.1-i).
3. **replay 는 발행 권한 계약을 필연적으로 넘는다 (→ P3, 신규).** 소비 경로 원장의 `origin_topic` 은 **정의상 남이 발행한 토픽**이다 — order 의 소비 원장은 `payment.*`/`product.updated`/`stock.reservation.result` 뿐이고, 자기 발행 토픽의 행은 **quarantine 경로에만** 생긴다. "원본 토픽 producer 만 발행한다" 를 replay 에 그대로 적용하면 replay 가능 집합이 quarantine 행으로 축소되는데, 그 행들은 §2.2-5 때문에 오히려 금지 대상이다 → 처분 필요(N3).
4. **replay 는 실패하지 않은 group 까지 재전달한다 (→ P5, 신규).** 원본 토픽에 다시 넣으면 그 토픽의 **모든** group 이 다시 받는다(§2.1-n). 억제 수단은 `processed_events (event_id, consumer_group)` 뿐이므로 **동일 `eventId` 보존이 필수 불변식**이다.
5. **금지 축은 3개의 독립 조건이다 (→ P5, §정정 이력 ③).** 초안은 "quarantine = eventId 부재" 로 묶었으나 §2.1-o 가 이를 반증했다. 실제로는 ① `event_id IS NULL`(타 group 멱등 억제 불가) ② `failed_consumer_group='__unknown__'`(어느 group 이 실패했는지 모름 → replay 대상 판정 불가) ③ `origin_kind='DLQ_ORIGIN'`(좌표가 `.dlq` **자신**이라 원본 레코드를 읽을 수 없다 — 그대로 replay 하면 **`.dlq` 내용을 원본 토픽에 주입**하는 사고) 가 **서로 다른 조건**이고 조합이 가능하다.
6. **P3 의 충돌 대상은 ADR-0011 이 아니라 ADR-0012 D4 + ADR-0018 이다 (§2.1-t, §정정 이력 ④).** ADR-0011 D2 는 NewTopic 프로비저닝 소유이며 replay 는 그것을 건드리지 않는다 → **ADR-0011 Status 는 불변**, 관계 설명만 남긴다.
7. **`RESOLVED` 가 false-green 이 되기 쉽다 (→ P7, N9).** broker ack 는 *재발행이 성공했다*만 증명한다. 원래 실패한 consumer 의 업무 트랜잭션 성공은 증명하지 않는다. 이 신호로 `RESOLVED` 를 닫으면 `REPLAY_PUBLISHED` 와 구분이 없다.
8. **Status 판정은 복수 superseder 를 보존해야 한다 (→ P8, §2.1-u).**
9. **replay 는 순서를 복원하지 않는다 (→ P5, 신규).** 재발행된 레코드는 로그 **끝**에 붙는다. 실패했던 group 은 `processed_events` 행이 없으므로 반드시 실행하는데, 그 시점의 도메인 상태는 이미 앞으로 나가 있다. "늦게 도착한 과거 이벤트" 를 허용할지는 **토픽·eventType 별 결정**이다.
10. **멱등 안전창이 좌표 창과 다르다 (→ P5, §2.1-r).** 좌표가 남아 있어도 `processed_events` 가 정리됐으면 §2.2-4 의 억제가 무력해진다. replay 허용 조건에 **만료 시각 검사**가 필요하다.
11. **ADR 처분은 채택안에 종속된다 (→ P8, 2R #9).** P2 가 ②(위임)를 택하면 ADR-0012 **D1 은 안 바뀐다**. P3 이 ②(producer 위임)를 택하면 **D4 producer 컬럼도 안 바뀐다**. 따라서 상단·§6 이 "D1·D4 개정" 을 **확정 사실로 쓰면 안 된다** — 실제로 기존 결정을 무효화하는 안을 채택했을 때만 부분 무효화를 수행한다.
12. **replay 후 재실패가 원장을 갈라놓는다 (→ P5·P7, N11, 2R #2).** 재발행된 레코드를 업무 consumer 가 또 실패시키면 `DeadLetterRecorder:54-69` 가 **새 좌표(새 offset)** 로 별도 원장 행을 `OPEN` 으로 만든다. 상관키를 읽는 경로가 없으므로 **원래 행은 발행 성공 상태에 남고 새 행만 미결**이 된다 — 같은 사건이 두 행으로 갈라지고, 원래 행 쪽에서 보면 종결처럼 보인다.
13. **`replay_deadline` 의 기준시각을 고를 수 있는 자유가 함정이다 (→ P5, N12, 2R #4, §2.1-y).** 다른 group 의 멱등 행 삭제 시각은 **그 서비스 로컬 시각** 기준이고 우리는 그것을 조회할 수 없다. 원장 `occurredAt + retention` 을 쓰면 **DLQ 도달이 늦은 사건**에서 다른 group 의 멱등 행이 이미 지워져 있을 수 있다. `originalTimestamp` 는 nullable 이고 미래값·clock skew 처리도 없다. 보수적 계산식이 **결정 사항**이다.
14. **ADR-0018 이 두 개념을 결합해 논증했다 (→ P3, 자체 발견).** §2.2-6 은 "프로비저닝 소유 ≠ 발행 권한" 이라고 정정했는데, `0018:48`·`0018:161` 은 그 둘을 **한 논증 안에서 묶어** 쓴다 — "producer 가 2개가 되어 `NewTopic` 프로비저닝 소유자와 payload 스키마 소유가 모호해진다". 즉 두 개념이 별개인 것은 맞지만 **ADR-0018 이 의도적으로 결합했고, replay 가 그 결합을 처음 끊는 사례**다. P3 은 "대상 ADR 을 잘못 지목했다" 로 끝나지 않고 **그 결합을 끊는 결정**으로 써야 한다. 한편 `0018:230` 은 **"기존 결정을 부정하지 않고 같은 규칙으로 항목을 더하는 것은 부분 무효화가 아니다"** 라는 선례를 남겼다 — P8 의 Status 판정은 이 선례와 대조해야 한다.

### 2.3 제약 / 트레이드오프

- **본 task 는 문서 + 최소 계약 설정만 변경한다.** replay 컬럼·마이그레이션·poller 분기·좌표 reader·관리 API 는 구현 ④-c-2b 다. ADR-0018 전례와 동일하되 **P4 만 예외로 코드에 손을 댄다** — D4 의 선결 조건으로 명시돼 있어(초안 §4-2), ADR 이 "7일 안의 좌표는 읽을 수 있다" 를 전제하려면 그 전제가 먼저 참이어야 한다.
- **ADR immutable 원칙**: ADR-0012 D1·D4 를 바꾸므로 본문 수정이 아니라 신규 ADR + Status 판정이 필요하다(P8).
- **자동 재발행은 영구 금지**(부모 §2.3-B · `docs/04-design-deep-dive.md:445-466`). 어떤 결정도 이걸 뒤집지 않는다.
- **검증 환경**: 다중 브로커·토픽 재생성·compaction hole·운영 클러스터 ACL 은 단일 브로커 Testcontainers 에서 재현되지 않는다. §5 에서 재현 가능한 것과 아닌 것을 가르고, 아닌 것은 §7 에 남긴다.

---

## 3. 작업 항목

> 각 항목의 산출물은 **ADR-0020 의 해당 절**이다. 결론만 적지 않고 **선택지 · 채택 · 근거 · Consequences(포기한 것 포함)** 를 쓴다(N1).

### P1. D1 — 재발행 보장 수준
- **먼저 못박는다: DB↔Kafka 원자성을 제공하는 선택지는 없다.** Kafka 트랜잭션은 *Kafka 내부 쓰기와 offset 커밋*의 원자성이며, `OutboxPollingService:83-86` 의 "broker ack ↔ DB save" 경계를 없애지 못한다(1R #6). 이걸 "exactly-once 대안" 으로 제시하면 비교 자체가 허위가 된다.
- **비교축을 두 종류로 가른다 (2R #5)**:
  - **구조 변경 대안**(crash window 를 실제로 줄이는가): ① 현행 폴링 outbox + at-least-once / ② **CDC(log-based outbox, Debezium)** — poller 의 별도 save 자체를 제거하지만 **새 인프라 의존**
  - **비해결 대조군**: **Kafka 트랜잭션**. 단일 레코드 outbox 에서 이 창을 **줄이지 않는다**. 소비자 가시성(`read_committed`) 제어일 뿐이므로 "crash window 를 줄이는 수단" 목록에 넣지 않고 *왜 답이 아닌지*를 기록하는 자리에 둔다
- 비용을 **코드 사실대로** 적는다(§2.1-v·§2.1-x): `isolation.level` 전파 대상은 Kafka consumer 가 있는 **order·product·payment·notification 4서비스**(User 는 `KafkaAutoConfiguration` 제외라 무관) · poller 는 **기존 3개 + P2 가 notification outbox 를 채택하면 1개 추가** 로 조건부 표기 · 단일 브로커 e2e 영향 · (CDC) 새 컴포넌트 운영·k8s·관측
- **최종 보장 문구는 어느 안이든 publication at-least-once 로 고정**하고, "중복 발행 0" 을 계약에서 삭제한다(N2). 대신 *중복 발행이 일어남을 전제로 소비 효과가 1회* 임을 보장한다.
- replay 실패 시 재시도 상한·`attempt_count` 와의 관계를 규정

### P2. D2 — notification-service 의 outbox
- 선택지 ① *notification 에 `outbox_events` 신설 → **ADR-0012 D1 개정*** / ② *replay 지휘를 다른 서비스에 위임 → DB 간 durable command 채널*
- ②의 실제 범위를 축소 기술하지 않는다 — §2.2-3 아래에서 이는 notification 만의 문제가 아니라 **4서비스 전부에 걸린 채널** 신설이다(1R 맥락)
- 채택안이 ①이면 산출물(Flyway·`global/outbox` 패키지·poller·스케줄러·배선·롤링 배포 순서)을 Consequences 에 명시하고 **구현은 2b 소관**임을 못박는다

### P3. D2' — replay 발행 주체와 `1 topic = 1 producer` (§2.2-3·§2.2-6, 신규)
- **대상 ADR 을 정확히 지목한다** — ADR-0012 D4 매트릭스의 producer 컬럼 + ADR-0018. **ADR-0011 D2 는 NewTopic 프로비저닝 소유**라 replay 와 무관하며 Status 불변(§2.1-t)
- **프로비저닝 소유 ≠ 발행 권한** 을 ADR 에서 용어로 분리 정의하되, **ADR-0018 이 그 둘을 결합해 논증했다는 사실**(`0018:48`·`:161`, §2.2-14)을 명시하고 **replay 가 그 결합을 끊는 첫 사례**임을 기록한다. 결합을 끊는 것이 이 항목의 결정 내용이지, 단순한 용어 정리가 아니다
- 선택지 ① *replay 발행을 발행 권한 규약의 **명시적 예외**로 규정(원장 소유 서비스가 자기 행에 대해서만, 동일 `eventId`·동일 key·payload 무변경)* / ② *producer 서비스 위임(= P2 ②와 같은 채널 비용)*
- 예외로 간다면 fence 를 계약으로: 자기 원장 행 한정 · payload 변경 금지 · `record_kind=REPLAY` 표식 · 소유 위반 시 발행 거부
- ADR-0012 D4·ADR-0018 의 처분(refine / 부분 무효화)을 P8 로 넘긴다

### P4. D4-a — 브로커 retention 계약을 실제로 고정 (**유일한 코드 변경**)
- **현재 실효값을 관측으로 확정한다** — `AdminClient.describeConfigs` 로 업무 토픽 1개 + `.dlq` 1개의 `retention.ms`·`retention.bytes`·`cleanup.policy` 값과 `ConfigSource`(DEFAULT_CONFIG / STATIC_BROKER_CONFIG / DYNAMIC_TOPIC_CONFIG)를 증적으로 남긴다. §2.1-h 의 이미지 파일 근거를 **런타임 관측으로 승격**
- 업무·`.dlq` 토픽 전부의 `NewTopic` 에 `.config(RETENTION_MS_CONFIG / CLEANUP_POLICY_CONFIG / RETENTION_BYTES_CONFIG)` 선언. 값은 ADR 이 정하고 `app.idempotency.floor.kafka-topic-retention` 과 **같은 출처에서 유도**되게 한다(두 곳에 7d 를 따로 적으면 갈라진다)
- **기존 토픽 반영 경로** — `spring.kafka.admin.modify-topic-configs=true`. ADR-0007 상 *환경별 연결정보가 아니라 동작 규약*이므로 **base `application.yml` 또는 Java Config**(프로파일 금지)
- **미선언 config 의 처분을 관측하고 acceptance 로 고정**한다(P4-4)
- **용량 트레이드오프를 닫는다 (1R #10, 2R #6 정정)** — `kafka-pvc` 는 현재 **1Gi**(§2.1-s), 업무 토픽 3파티션·`.dlq` 1파티션·`replicas(1)`(§2.1-z). 초안은 "유한 `retention.bytes` 면 7일 전에 지워진다" 고 단정했으나 **그건 값이 파티션별 7일 유입량보다 작을 때만 참**이다. **두 하한을 각각 증명한다**:
  1. **파티션별 하한** — 토픽·파티션별 최악 유입 바이트 × 7일 ≤ `retention.bytes`
  2. **디스크 하한** — 위 값을 **모든 파티션 × 복제수**로 합산하고 segment/인덱스 여유를 더한 값 ≤ PVC usable capacity
- 1 을 만족해도 2 가 깨지면 브로커가 죽는다. 둘은 별도 검증이다. **"유한 `retention.bytes` + 디스크 경보" 는 보장 수단이 아니다** — 경보는 PVC 용량을 늘리지 않는다. 운영 보조로 분류한다
- 결론은 ① PVC 증설 ② **best-effort 강등** 둘 중 하나다. 근거를 못 대면 "7일 좌표 가용 보장" 을 버리고 **best-effort + 사전 좌표검증**으로 보장 문구를 낮춘다
- **배포 게이트를 명시한다 (1R #7)** — 로컬 성공은 운영 Kafka 의 `ALTER_CONFIGS` 권한·기존 dynamic override·서비스별 배포 결과를 증명하지 않는다. 서비스 배포 순서 · 권한 실패 시 중단 조건 · 이전 dynamic config 복구 명령 · 롤백 검증을 적는다
- `.dlq` 토픽의 retention 이 원본과 같아야 하는지 결정(원장이 좌표를 들고 있으므로 `.dlq` 자체 보존은 진단용)

### P5. D4-b + D5 — 좌표 유효성 · replay 금지 정책 · 재적용 의미론
- **좌표 검증 계약**: replay 전 `AdminClient` 로 `cleanup.policy`·`retention.ms`·beginning/end offset 조회 → 요청 좌표가 `[beginning, end)` 밖이면 **replay 불가로 종결**. 읽은 뒤 **반환 레코드 offset == 요청 offset** 검증(compaction hole 에서 seek 은 다음 레코드를 준다)
- **금지 축을 5개 독립 조건으로 규정한다 (N6, §2.2-5)**:
  1. `event_id IS NULL` — 타 group 의 멱등 억제 불가
  2. `failed_consumer_group = '__unknown__'` — 어느 group 이 실패했는지 모름
  3. `origin_kind = 'DLQ_ORIGIN'` — 좌표가 `.dlq` 자신이라 원본을 읽을 수 없다. **replay 하면 `.dlq` 내용이 원본 토픽에 들어간다**
  4. **좌표 무효** — `topic_generation` 불일치 · offset 범위 밖 · offset 불일치 반환
  5. **토픽/eventType 별 `replayPolicy`** — 정책적 금지. `payloadTruncated` 와 **독립 컬럼**
  조합별 처분(예: 1 과 2 가 동시에 참)도 표로 적는다
- **멱등 안전창과 그 기준시각 (1R #2 · 2R #4, N12, §2.2-13)**: `processed_events` 는 **각 서비스 로컬 시각** 기준 무조건 삭제다(§2.1-r·y). 원장에 **`replay_deadline`** 을 두고 `now < replay_deadline` 을 강제하되, **기준시각을 ADR 결정으로 격상한다** — "원장 `occurredAt` 기준이든 원본 발생시각 기준이든 아무거나" 는 결정이 아니다. 규정할 것:
  - **보수적 계산식** — 다른 group 의 성공 시각은 분리 DB 안이라 조회 불가하므로, 관측 가능한 시각 중 **가장 이른 것**을 기준으로 삼는다(원본 발생시각 ≤ DLQ 적재 `occurredAt`). `occurredAt` 을 쓰면 **DLQ 도달이 늦은 사건**에서 다른 group 의 멱등 행이 먼저 지워질 수 있다
  - **신뢰할 timestamp 출처와 순위**, `originalTimestamp` **nullable 시 처분**, **미래값** 거부, **서비스 간 clock skew** 여유
  - `retention` 은 replay 창보다 **안전 여유를 두고 길게** 잡는다
  - 실패 주입: *다른 group 이 먼저 처리 → DLQ 도달 지연 → 그 group 의 멱등 행 삭제* 경계를 2b 필수 검증으로 명시
- **모든 group 재전달 불변식 (N4)**: 무해성의 근거는 오직 `processed_events (event_id, consumer_group)` 이며, **동일 `eventId` 보존이 필수**임을 계약으로 못박는다
- **순서 역전과 재적용 (1R #9, §2.2-9)**: replay 는 순서를 복원하지 않고 **과거 이벤트를 로그 끝에 추가**한다. 실패했던 group 은 멱등 행이 없어 반드시 실행하며, 도메인 상태는 이미 앞서 있다. `replayPolicy` 에 **상태 사전조건 · 늦은 이벤트 허용 여부**를 포함한다
- **재실패의 귀착 (N11, 2R #2, §2.2-12)** — 재발행된 레코드를 업무 consumer 가 **또 실패**시키면 `DeadLetterRecorder:54-69` 가 새 좌표로 **별도 원장 행**을 만든다. 결정할 것: ① **replay 상관키**가 *발행 헤더 → 업무 실패 → DLT 헤더* 까지 보존되는 계약 ② `DeadLetterRecorder` 가 그 상관키로 **원래 행을 `REPLAY_FAILED` 로 연결**하는 규칙 ③ 새로 생긴 DLQ 행의 처분(보존 / 원래 행에 병합 / 링크만). 이 셋이 없으면 같은 사건이 두 행으로 갈라지고 원래 행은 종결처럼 보인다
- `topic_generation` 을 무엇과 대조하는지 결정(설정값 대 브로커 관측)

### P6. D3 — replay 레코드의 표현
- 선택지 ① *`outbox_events` 에 additive 컬럼 + poller kind 분기* / ② *별도 `replay_outbox` 테이블 + 별도 poller*
- 비용: ②는 poller·스케줄러·메트릭·retention 잡이 2벌 / ①은 도메인 행에 항상 NULL 인 컬럼
- 필요한 표현: `record_kind` · `destination_topic` · **nullable 임의 key**(현 `aggregate_id` 는 NOT NULL·50자) · `destination_partition` · header allowlist · raw payload
- **추적키 분리**: outbox 행의 `event_id`(unique, 발행 추적)와 **재발행 대상 payload 의 `eventId`**(보존 필수, P5)를 서로 다른 컬럼으로. 같은 컬럼이면 unique 가 "같은 레코드의 2회 replay" 를 사고로 막거나 도메인 이벤트와 충돌한다
- header allowlist 근거: `DLT_*` 헤더를 그대로 실으면 재실패 시 원본 좌표가 오염된다
- **마이그레이션·호환 계약 — 표를 둘로 가른다 (1R #13, 2R #3 정정)**: 초안은 outbox 변경만 "3~4개 DB" 로 뭉뚱그렸다. 실제로는 **두 축이 다르다**(§2.1-x):
  - **outbox 스키마** — 현재 **3 DB**(order/product/payment), P2 가 notification outbox 를 채택하면 **4 DB**
  - **원장(`dead_letter_records`) 스키마** — `order V6`·`product V5`·`payment V5`·`notification V3` 로 **채택안과 무관하게 항상 4 DB**. P5 의 `replay_deadline`·상관키(§2.2-12)와 P7 의 `outbox_event_id`·감사시각(`replayed_at`/`replayed_by`)·fence 컬럼(`@Version`)이 전부 여기 붙는다(부모 계획 §3 이 이미 열거)
  - 컬럼별로 **expand → deploy → 검증 → (선택) contract** 순서 · 기존 행의 기본 의미 · nullable→NOT NULL 전환 시점 · 구버전 애플리케이션 호환성을 적는다

### P7. D6 + D7 — 종결 실행자 · 관측 회귀 · 운영 진입점
- **발행 lifecycle 과 사건 resolution 을 분리한다 (N9, 1R #1 → 2R #1 정정).** 1R 은 `RESOLVED` 를 **무조건 추가**하면서 동시에 선택지 ②가 *`RESOLVED` 를 두지 않는다* 고 적어 **자기모순**이었다. 더 중요한 것은 ②가 broker ack 상태인 `REPLAY_PUBLISHED` 를 terminal 로 삼는다는 점이다 — 그걸 unresolved 집계에서 빼면 **N9 가 막으려던 조기 종결과 의미가 같다**. 두 축을 갈라 결정한다:
  - **축 A — 발행 lifecycle**: `REPLAY_REQUESTED` → `REPLAY_PUBLISHED` / `REPLAY_FAILED`. 근거는 broker ack 와 outbox 상태다
  - **축 B — 사건 resolution**: 실패했던 consumer 의 업무 처리가 성공했는가. 근거는 **오직 소비 성공 확인**이다
  - 선택지 ① *업무 consumer 가 replay 상관키를 받아 **비즈니스 처리와 같은 트랜잭션에서** 성공 확인을 기록 → `RESOLVED` 존재* / ② *소비 성공 확인을 만들지 않음 → **`RESOLVED` 를 두지 않고**, `REPLAY_PUBLISHED` 를 **terminal 로도 non-unresolved 로도 삼지 않는다*** (즉 발행됐어도 사건은 계속 미결로 보인다)
  - ①의 비용(상관키 전파 · 4서비스 consumer 변경 · 회신 경로)을 적고 택일한다. **상태 집합 자체를 채택안에 조건부로 기술**한다 — 두 안의 상태 목록이 다르다
- **관측·정리 회귀를 필수 산출물로 (N10, 1R #5)** — `DeadLetterRecordJpaRepository:77-95` 가 상태 집합을 리터럴로 박고 있다. 새 상태 각각에 대해 **unresolved 로 세는가 / terminal 인가 / purge 대상인가** 를 분류표로 만들고, `countUnresolved`·`findOldestUnresolvedOccurredAt`·`findStaleUnresolved`·purge 쿼리 · `DeadLetterMetrics` · `DeadLetterMaintenanceScheduler` · actuator · 경보 · runbook 쿼리의 변경을 2b 산출물로 명시한다.
  - 회귀 테스트는 `REPLAY_FAILED`·장기 `REPLAY_REQUESTED` 만으로는 **부족하다**(2R #1) — 채택안 ②에서는 **`REPLAY_PUBLISHED` 가 backlog·age 에 계속 잡히는지**가 핵심 단언이다. 그것을 빼먹으면 상태 이름만 바꾼 조기 종결이 그대로 통과한다
- **종결 주체**: 원장 ↔ outbox 를 잇는 `outbox_event_id` · 전이 주체 1종 확정(관리 API 동기 확인 / reconciler 폴링) · outbox `FAILED` 소진 시 원장 처분
- **동시 요청 fence (1R #13, §2.1-q)**: `DeadLetterRecord` 에 `@Version` 이 없다. 같은 원장 행에 동시 replay 요청이 와도 **outbox 행이 하나만 생성**됨을 조건부 상태 UPDATE · 행 잠금 · `@Version` 중 하나로 계약하고 테스트 항목으로 둔다
- **진입점 1종 확정**: 운영 CLI vs Actuator `@WriteOperation`. `DeadLetterEndpoint` 가 이미 write 진입점을 가졌고(§2.1-l) 2a runbook 이 curl 기반이라는 사실을 근거로 비교. 인증·권한·트랜잭션 경계 포함
- **직접 SQL 상태 변경 금지**를 계약으로 — 2a 에서 runbook 이 도메인 가드를 우회하도록 지시하던 결함이 실제로 났다. 리허설은 공개 진입점만 사용한다

### P8. ADR Status 판정과 문서 배선
- `docs/adr/0020-dlq-replay-contract.md` 작성 (`docs/adr/template.md` 준수)
- `docs/adr/README.md` 인덱스 행 추가
- **ADR-0012 — 처분은 채택안에 종속된다 (2R #9, §2.2-11)**: D1 은 **P2 가 ①(notification outbox 신설)을 택했을 때만**, D4 producer 컬럼은 **P3 이 ①(발행 권한 예외)을 택했을 때만** 바뀐다. 위임안을 택하면 **둘 다 안 바뀌고 Status 도 불변**이다. 실제로 무효화하는 경우에만 부분 무효화를 수행하고, **이미 ADR-0016 에 의해 `Partially Superseded` 이므로 복수 superseder 를 모두 보존하는 표기**를 쓴다(N7, §2.1-u)
- **ADR-0018**: `1 topic = 1 producer` 규약(`:48`·`:161`)과의 관계 판정. **`0018:230` 의 선례와 대조한다** — "기존 결정을 부정하지 않고 같은 규칙으로 항목을 더하는 것은 부분 무효화가 아니다". replay 예외는 *항목 추가*가 아니라 *규칙에 예외를 내는 것*이므로 이 선례가 그대로 적용되지 않는다는 점을 논증하거나, 적용된다면 Status 불변으로 간다(§2.2-14)
- **ADR-0011**: **Status 불변**. replay 는 NewTopic 프로비저닝 소유를 건드리지 않으므로 관계 설명만 남긴다(§2.2-6)
- `task-impl4-c2b-dlq-replay.md` §2 를 "ADR-0020 이 확정함" 으로 갱신하고 §4 착수 조건 1·2 를 닫는다
- `docs/runbooks/dlq-recovery.md` §6 "재발행 — 현재 불가" 는 **그대로 둔다**(구현은 2b). 링크만 계획서 §2 → ADR-0020 으로 교체
- Layer 1: `docs/04-design-deep-dive.md:445-466` 에 `(see ADR-0020)` 참조 추가

---

## 4. PR 분할

| PR | 범위 | 분할 근거 |
|---|---|---|
| **④-c-2b-adr** | P1·P2·P3·P5·P6·P7·P8 | 문서만. 리뷰 축 = 결정의 정합성 |
| **④-c-2b-0** | P4 | 유일한 코드 변경. 리뷰 축 = *배포된 브로커에 실제로 적용되는가* |

- 순서 고정: ADR 이 값을 정하고 → P4 가 그것을 고정. 뒤집으면 P4 가 근거 없는 숫자를 박는다.
- P4 를 2b 본체로 미루지 않는 이유: 2b 의 `/plan` 이 "7일 안의 좌표는 읽힌다" 를 **전제로** 쓰이는데, 그 전제가 계약이 아니면 계획 전체가 미검증 가정 위에 선다.

---

## 5. 검증 방법

> **판정 방식을 두 종류로 가른다 (2R #7).** 1R 은 모든 행에 실패 주입을 요구했는데, 문서 결정 항목에는 주입할 실행 표면이 없어 §5 가 N8 과 자기모순이었다.
>
> - **[구조]** — 문서 결정 항목. **선택지 / 채택 / 근거 / Consequences 구조가 갖춰졌는가**로 판정한다. 실패 주입 대상이 아니다.
> - **[변이]** — 실행 표면(코드·설정·운영 주장). **실제 mutation 을 가해 red 가 뜨는지**로 판정한다. red 조건 없는 [변이] 행은 미완이다(N8).

| 항목 | 검증 | 실패 주입 / red 조건 |
|---|---|---|
| P1 | **[구조]** 보장 문구에 "중복 발행 0" 이 **없고**, Kafka 트랜잭션이 *비해결 대조군*으로 분리됐으며, 비용 수치가 §2.1-v·x 와 일치하는가(4서비스·poller 3+조건부 1) | 보장 문구가 exactly-once 를 주장하면 N2 위반. Kafka 트랜잭션을 "crash window 축소 수단" 목록에 넣었으면 2R #5 재발. *grep 자동판정은 기각 문구까지 히트하므로 쓰지 않는다(2R #7)* |
| P2 | **[구조]** ②의 범위가 "notification 1개" 가 아니라 4서비스 채널로 기술됐는가 · 채택안이 D1 을 실제로 바꾸는지 판정됐는가 | 축소 기술이면 되돌린다 |
| P3 | **[구조]** 프로비저닝 소유 ↔ 발행 권한 용어 분리 · **ADR-0018 이 둘을 결합해 논증했다는 사실과 replay 가 그 결합을 끊는다는 판정**(§2.2-14) · 예외 fence 목록 · ADR-0012 D4/ADR-0018 처분 | ADR-0011 Status 를 바꿨으면 §2.2-6 위반. 결합 사실을 언급하지 않고 "대상 ADR 정정" 으로만 끝나면 미충족 |
| **P4-1** | **[측정]** 기준선 — compose 기동 후 `describeConfigs` 로 업무 토픽 1 + `.dlq` 1 의 3개 config 값과 `ConfigSource` 를 증적 파일에 기록. **판정이 아니라 P4-2/3 의 대조 기준**이므로 red 조건이 없다(2R #7) | — |
| **P4-2** | **신규 토픽 경로** — 없는 토픽명으로 `NewTopic` 선언·기동 → 선언값 + `DYNAMIC_TOPIC_CONFIG` 반환 | `.config(...)` 제거 시 이 단언이 **실패해야** 한다(1회 확인) |
| **P4-3** | **기존 토픽 경로 (핵심)** — **자동 통합테스트**로: 지속되는 단일 Testcontainers 브로커에 ① 옛 config 로 토픽 생성 → ② 새 config 선언 + `modify-topic-configs=false` 컨텍스트 기동 → **옛 값 유지 단언(red 재현)** → ③ `true` 컨텍스트 순차 기동 → **새 값 단언(green)**. 두 컨텍스트가 같은 브로커를 쓰는 것이 조건 | `false` 단계가 새 값을 보이면 테스트 자체가 무효 → 즉시 실패 |
| **P4-4** | **미선언 config 의 처분 — acceptance criterion** | `NewTopic` 에 없는 config 를 dynamic 으로 미리 심고 `modifyTopicConfigs=true` 기동 → **보존되면 pass**. **삭제되면 fail** 이며, 그때는 (a) 모든 dynamic config 를 선언적으로 소유하거나 (b) modify 전략을 교체할 때까지 **red 를 유지한다**. "사실대로 적으면 통과" 는 판정이 아니다(1R #8) |
| **P4-5** | `app.idempotency.floor.kafka-topic-retention` 과 토픽 선언값이 갈라지면 기동 실패/lint red | 한쪽만 바꿔 red 확인 |
| **P4-6** | **용량 판정** — `retention.bytes` 결정과 PVC 1Gi 의 정합 근거(유입량 산정)가 ADR 에 있는가. 없으면 보장 문구가 best-effort 로 낮아졌는가 | 근거도 없고 문구도 안 낮췄으면 미충족 |
| **P4-7** | **배포 게이트** — 운영 적용 증적(전 토픽 `describeConfigs`)이 release gate 로 명시됐거나, P4 완료 범위가 "로컬 기존 토픽 경로 검증" 으로 낮춰졌는가. 롤백 명령·권한 실패 중단 조건 포함 | 둘 다 아니면 N5 와 완료 판정 사이가 비어 있다(1R #7) |
| P5-a | **[구조]** 금지 축 5종이 **독립 조건**으로 기술되고 조합 표가 있는가. `payloadTruncated` 가 금지 사유 목록에 **없는가** | `payloadTruncated` 가 금지 사유로 남으면 N6 위반 |
| P5-b | `origin_kind='DLQ_ORIGIN'` 금지가 명시됐는가 | 누락 시 `.dlq` 내용이 원본 토픽에 주입되는 경로가 열린다 |
| P5-c | `replay_deadline`(멱등 만료) 검사와 `retention` 여유 규칙이 있는가 | 누락 시 1R #2 재발 |
| P5-d | 모든 group 재전달 불변식(동일 `eventId` 필수) + **순서 역전/늦은 이벤트 처분**이 적혔는가 | 어느 하나 누락이면 N4 위반 |
| P6 | **[구조]** 추적키 2종이 **다른 컬럼**인가 · **outbox(3~4 DB) 와 원장(항상 4 DB) 마이그레이션 표가 분리**됐는가(§2.1-x) · 컬럼별 expand→contract 순서와 구버전 호환 | 같은 컬럼이면 미충족. 원장 변경을 outbox 표에 섞어 적으면 2R #3 재발 |
| P7-a | **[구조]** 발행 lifecycle(축 A) 과 사건 resolution(축 B) 이 분리됐는가 · 상태 집합이 **채택안 조건부**로 기술됐는가 | ack 를 resolution 근거로 삼았으면 N9 위반. `RESOLVED` 를 무조건 추가하면서 선택지 ②가 그것을 부정하면 2R #1 재발 |
| P7-b | 새 상태별 **unresolved/terminal/purge 분류표**와 쿼리·메트릭·경보·runbook 변경 목록이 2b 산출물로 있는가 | 누락 시 N10 위반 |
| P7-c | 동시 replay 요청 fence(조건부 UPDATE / 잠금 / `@Version`) 계약과 테스트 항목이 있는가 | 누락 시 미충족 |
| P7-d | **[구조]** 진입점 1종 확정 · 직접 SQL 금지 문구 | 어느 하나 누락이면 미충족 |
| P8 | **[구조+변이]** ADR-0012 처분이 **채택안에 종속**으로 판정됐는가(위임안이면 Status 불변) · 무효화한다면 **ADR-0016·ADR-0020 두 superseder 를 모두 보존**하는 표기인가 · ADR-0011 Status 불변인가 · README 갱신 · 2b 계획서 §4 조건 1·2 종결 · runbook 링크 교체 | `grep -n "task-impl4-c2b-dlq-replay.md.*§2" docs/runbooks/dlq-recovery.md` 히트 시 미충족. ADR-0016 표기가 사라졌으면 N7 위반 |
| 전역 | **[변이]** **`settings.gradle` 에 포함된 전 subproject 빌드 그린**(현재 10개 — §2.1-w). 숫자 게이트를 쓰지 않고 설정 파일과 대조한다 · lint 전종 그린 (P4 가 `NewTopic` 을 건드리므로 `dead-letter-schema-parity-lint` 및 토픽 계약 테스트 회귀 확인) | 모듈 목록을 하드코딩해 두 모듈을 빠뜨려도 통과하면 2R #8 재발 |

---

## 6. 완료 조건

1. §1 의 N1~N10 이 **전부 거짓**임을 §5 의 관측으로 보일 수 있다.
2. `docs/adr/0020-dlq-replay-contract.md` 가 D1~D7 전부에 대해 **선택지·채택·근거·Consequences** 를 갖는다.
3. ADR-0012(복수 superseder 보존)·ADR-0018 의 Status/관계가 갱신되고 README 가 그것을 반영한다. ADR-0011 은 Status 불변.
4. P4 의 retention 계약이 **기존 토픽에서** 실제로 적용됨을 자동 테스트의 red→green(P4-3)으로 확인했고, 미선언 config 처분이 acceptance(P4-4)로 고정됐으며, 운영 적용은 release gate 로 명시되거나 완료 범위가 명시적으로 낮춰졌다(P4-7).
5. `task-impl4-c2b-dlq-replay.md` §4 착수 조건 1·2 가 닫히고, 조건 3(2a 머지 — 이미 충족)만 남아 `/plan task-impl4-c2b-dlq-replay` 재실행이 가능하다.

---

## 7. 미해결 (범위 밖 · 처분)

1. **replay 구현 일체** — 컬럼·마이그레이션·poller kind 분기·좌표 reader·관리 API·리허설·관측 회귀. **구현 ④-c-2b** 소관.
2. **다중 브로커·복제 상황의 좌표 유효성** — 단일 브로커 Testcontainers 에서 재현 불가. `replicas(1)` 이 현 배포 계약이므로 그 범위에서만 검증하고 한계를 ADR Consequences 에 명시.
3. **compaction hole 실재현** — 전 토픽이 `cleanup.policy=delete` 로 고정되면 hole 은 발생하지 않는다. 그래도 좌표 검증 로직은 유지한다(정책이 바뀌면 조용히 깨지는 전제라서). 실재현은 미수행.
4. **GKE 실브로커 적용 확인** — 로컬 compose/Testcontainers 로만 검증한다. 실클러스터 적용·`ALTER_CONFIGS` 권한 확인은 P4-7 의 게이트로 명시하고, 수행은 구현 ③ PR3d-b-2 GKE 세션에 합류시킨다.
5. **`.dlq` 토픽 partition 수(현재 1)** — 변경은 재생성을 요구하고 `topic_generation` 계약을 건드린다. 본 task 범위 밖.
6. **PVC 증설 실행** — P4-6 이 필요하다고 판정하면 매니페스트 변경은 2b-0 이 아니라 인프라 변경으로 분리한다(용량 산정 근거를 ADR 에 남기는 것까지가 본 task).

---

## 정정 이력 (계획 리뷰 1R — 13건 전량 반영)

초안이 무엇을 틀렸는지 남긴다. 조용히 고치지 않는다.

| # | 초안의 진술 | 반증 | 처분 |
|---|---|---|---|
| ③ | "quarantine 행은 **정의상 `eventId` 를 판독하지 못한 레코드**" → 그래서 금지 축이 하나로 정리된다 | **거짓**. quarantine 조건은 `failed_consumer_group=='__unknown__'` 이고(`DlqOrigin:41-63`), `eventId` 는 `DeadLetterRecorder:57` 이 group 과 **독립적으로** payload 에서 뽑는다. 좌표 출처(`DlqOriginKind`)는 또 다른 축 | §2.1-o 신설 · §2.2-5 재작성 · **P5 금지 축을 5개 독립 조건으로** · N6 재작성 |
| ④ | "replay 는 **ADR-0011** producer-owns-topic 과 충돌한다" | **오지목**. ADR-0011 D2:71 은 **NewTopic 프로비저닝 소유**다. 발행 권한의 SSOT 는 **ADR-0012 D4 producer 컬럼 + ADR-0018** | §2.1-t·§2.2-6 · P3/P8 대상 교체 · **ADR-0011 Status 불변** · N3 수정 |
| ⑥ | D1 선택지 ②를 "**Kafka 트랜잭션(EOS)**" 으로 두고 exactly-once 대안처럼 제시 | **성립 안 함**. Kafka 트랜잭션은 Kafka 내부 원자성이며 `OutboxPollingService:83-86` 의 DB↔Kafka 경계를 없애지 못한다 | P1 재작성 — "원자성 제공 선택지는 없다" 를 먼저 못박고 ③ CDC 를 실제 대안으로 추가 |
| ⑧ | P4-4 를 "**사실대로 적으면**" 통과로 설계 | **자기 명제 N8 위반**. 위험한 결과(미선언 config 삭제)도 green 이 된다 | P4-4 를 **acceptance criterion** 으로 격상 — 삭제되면 red 유지 |
| ⑪ | §2.1-a 라인 인용 `85-88` · §2.1-b 컬럼 목록 | crash window 는 **83-86** · `last_attempted_at`·`created_at` 누락 | 표 수정 |
| ①⑤⑦⑨⑩⑫⑬ | (누락) | `RESOLVED` false-green · 새 상태의 관측/purge 소실(`Repository:77-95` 리터럴) · 운영 배포 게이트 부재 · 순서 역전/늦은 이벤트 · 멱등 만료창(`ProcessedEventCleanupScheduler:41`) · PVC 1Gi 용량 · 복수 superseder 보존 · 동시 요청 fence(`@Version` 부재) | N9·N10 신설 · §2.1 p~u 신설 · §2.2 7~10 신설 · P4/P5/P6/P7/P8 에 항목 추가 · §5 에 P4-6·P4-7·P5-b~d·P7-a~d 신설 |

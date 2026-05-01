# task-d010-outbox-trace-context — Outbox trace context 영속화 + Producer 헤더 전파

> 작성: 2026-05-01
> 관련 Phase: Phase 3 잔여 부채 (Phase 4 MSA 분리 진입 전 처리 권장)
> 관련 부채: D-010 (Observability) — `docs/TASKS.md` Tech Debt 표 행 D-010
> 관련 선행 작업: D-007 옵션 B (`MdcRecordInterceptor` 헤더 우선순위 도입, forward-compatible 인프라)
> 관련 ADR: 신규 ADR-0008 작성 예정 (본 task Part A)

## 1. 목표

Outbox 패턴 경유로 발행되는 모든 production Kafka 메시지에 **원본 HTTP 요청의 traceId/userId 를 영속·전파**하여 end-to-end 추적을 가능하게 한다. 본 task 는 모놀리스 단계의 Outbox-only 가시성 확보 범위로 한정하며, OpenTelemetry / W3C TraceContext 도입은 Phase 4+ 별도 ADR 로 분리한다.

세부 목표:

- **스키마 영속화** — `outbox_events` 테이블에 `trace_id`, `user_id` 컬럼 추가 (Flyway V4). 기존 행은 NULL 허용으로 무손실 호환.
- **MDC 캡처 시점 고정** — Outbox 이벤트 저장 시점(원본 HTTP 요청의 트랜잭션 컨텍스트) 에 MDC 스냅샷을 엔티티에 적재. `OutboxPollingService` 가 별도 스레드에서 publish 하더라도 trace context 가 보존되도록 한다.
- **Producer 헤더 주입** — `OutboxPollingService` 가 `KafkaTemplate.send(ProducerRecord)` 경로로 전환하여 `KafkaTraceHeaders.TRACE_ID` / `USER_ID` 헤더에 영속된 값을 주입. Consumer 측 `MdcRecordInterceptor` 의 기존 헤더 우선순위가 자동으로 동작하여 end-to-end MDC 가 묶인다.
- **회귀 방지** — D-001 (관측성 계약) 패턴과 동일하게, traceId 헤더 부재 시 eventId fallback 이 유지됨을 통합 테스트로 검증.

## 2. 배경 / 제약

- 현재 `OutboxPollingService.pollAndPublish()` 는 `@Scheduled(fixedDelay = 5000)` 로 별도 스레드에서 실행되며, `kafkaTemplate.send(topic, key, value)` 3-인자 시그니처만 사용 → ProducerRecord 미사용 → 헤더 주입 경로 없음 (`src/main/java/com/peekcart/global/outbox/OutboxPollingService.java:28-29`).
- `OutboxEvent.create()` (`src/main/java/com/peekcart/global/outbox/OutboxEvent.java:53-66`) 는 호출 시점의 MDC 를 캡처하지 않음. Publisher 호출 스레드는 HTTP 요청 스레드(MDC 보유) 이지만, 폴링 스레드는 MDC 가 비어 있음.
- D-007 옵션 B 에서 `KafkaTraceHeaders` (`X-Trace-Id` / `X-User-Id`) 와 `MdcRecordInterceptor` 가 **헤더 우선 → eventId fallback → UUID fallback** 순서로 forward-compatible 하게 정의됨 (`src/main/java/com/peekcart/global/kafka/MdcRecordInterceptor.java:14-22`). 본 task 는 이 인프라를 **소비**하는 측 (Producer 헤더 주입) 만 추가한다 — Consumer 측 코드 불변.
- `MdcFilter` (`src/main/java/com/peekcart/global/filter/MdcFilter.java:26-31`) 는 traceId(UUID 16자리) + userId(SecurityContext) 를 HTTP 요청 단위로 MDC 에 적재. 인증되지 않은 요청은 userId 부재 → `user_id` 컬럼 NULL 허용 필요.
- DLQ 경로 (`KafkaConfig.kafkaErrorHandler`, `src/main/java/com/peekcart/global/config/KafkaConfig.java:76-96`) 는 `DeadLetterPublishingRecoverer` 가 원본 record 의 헤더를 자동 복사하므로 추가 작업 불필요. 검증만 수행.
- Phase 4 차단 조건이 아니지만 **Phase 4 진입 전 권장** — MSA 분리 후 cross-service tracing (OpenTelemetry / micrometer-tracing) 도입 시 Outbox 측 trace 인프라가 선결되어야 표준 헤더(`traceparent`) 를 추가만 하면 동작.
- 본 task 비대상: **W3C TraceContext / OpenTelemetry 도입**, **payload envelope 스키마 변경**, **trace 별도 테이블 분리**, **Brave/Zipkin Sleuth 도입**.

## 3. 작업 항목

### Part A — ADR 작성 (설계 결정 immutable 기록)

- [ ] **P1.** `docs/adr/0008-outbox-trace-context-propagation.md` 작성 (`docs/adr/template.md` 복사)
  - **Status**: `Proposed` → 본 task 완료 시 `Accepted` 로 전환
  - **Decision**: "Outbox 이벤트 저장 시점에 MDC traceId/userId 를 엔티티 컬럼으로 캡처하고, polling publisher 가 ProducerRecord 헤더(`X-Trace-Id` / `X-User-Id`)로 주입한다."
  - **Alternatives Considered** (각 항목별 장/단/기각 사유)
    - A. 컬럼 추가 + MDC 캡처 + 헤더 주입 (선택)
    - B. payload JSON envelope 에 `traceContext` 필드 임베드 — envelope 스키마 변경, Consumer payload 파서 수정 필요, 기존 메시지 호환성 깨짐
    - C. 별도 `outbox_trace_context` 테이블 분리 — JOIN 비용 + 폴링 쿼리 복잡도 증가, 단일 도메인 가시성 가치 대비 오버킬
    - D. OpenTelemetry / W3C TraceContext (`traceparent`) 즉시 도입 — Phase 4 MSA 분리 + micrometer-tracing 도입과 묶음 작업이 자연스러움. 현 시점 단독 도입은 모놀리스 단일 컨텍스트에서 가치 대비 비용 과다
  - **Consequences**
    - 긍정: end-to-end 로그 추적 가능, DLQ 분석 시 원본 요청 식별, Phase 4 OpenTelemetry 도입 시 헤더 우선순위에 `traceparent` 추가만으로 forward-compat 유지
    - 부정: outbox_events row size 증가 (`trace_id` 16자 + `user_id` 가변), MDC 직접 의존 위치 1곳 신규 (Publisher 또는 OutboxEvent.create 시그니처 — Part B 설계 결정에 종속)
    - 후속: Phase 4 진입 시 `MdcRecordInterceptor` 헤더 우선순위에 `traceparent` 추가 + `MdcFilter` 를 OpenTelemetry tracer 와 결합. 본 ADR 은 그 단계에서 "Partially Superseded" 가 아닌 "Accepted" 유지 (Outbox 측 영속 컬럼은 그대로 활용)
  - **OutboxEvent ↔ MDC 결합 방향 결정 (ADR Decision 절에 명시)**
    - 옵션 (a): `OutboxEvent.create(...)` 시그니처에 `String traceId, String userId` 명시 인자 추가, Publisher 에서 `MDC.get()` 호출 후 전달
    - 옵션 (b): `OutboxEvent.create(...)` 내부에서 SLF4J `MDC.get()` 직접 호출
    - 본 계획서 기본 선택: **옵션 (a)** — 도메인 횡단 엔티티(`global/outbox/`) 가 SLF4J 에 직접 의존하지 않음, 테스트 시 명시 주입 가능. 트레이드오프 (Publisher 2개에서 MDC.get() 호출 중복) 는 ADR Consequences 에 기록

### Part B — 스키마 + 엔티티

- [ ] **P2.** Flyway 마이그레이션 추가
  - `src/main/resources/db/migration/V4__outbox_trace_context.sql` 신규
  - DDL: `ALTER TABLE outbox_events ADD COLUMN trace_id VARCHAR(64) NULL, ADD COLUMN user_id VARCHAR(64) NULL;`
  - **인덱스 추가 없음** — Outbox 폴링 쿼리는 `status='PENDING' ORDER BY created_at ASC` 기반(`OutboxEventJpaRepository.java:11-12`), 기존 인덱스 `idx_outbox_status_created` (`V2__outbox_processed_events.sql:17`) 가 이를 커버. trace 기반 조회는 사후 ad-hoc 분석용이라 인덱스는 row insert/update 비용만 증가. 필요 시 후속 ADR 로 분리
  - 기존 행 NULL 허용으로 backfill 불필요 — 신규 이벤트부터 채워짐
- [ ] **P3.** `OutboxEvent` 엔티티 갱신
  - `private String traceId; private String userId;` 컬럼 매핑 추가 (둘 다 nullable)
  - `create(...)` 시그니처에 `String traceId, String userId` 인자 추가 (Part A 옵션 (a))
  - getter 자동(@Getter), MDC 캡처 책임은 Publisher 로 위임
  - `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 유지 (JPA reflection 호환)
  - **호출부 전수 갱신**: `rg "OutboxEvent.create" src/main/java src/test/java` 로 모든 호출부 식별 후 새 시그니처로 컴파일 통과 보장. 현재 식별된 호출부:
    - `src/main/java/com/peekcart/order/infrastructure/outbox/OrderOutboxEventPublisher.java:59` (P4 에서 갱신)
    - `src/main/java/com/peekcart/payment/infrastructure/outbox/PaymentOutboxEventPublisher.java:52` (P4 에서 갱신)
    - `src/test/java/com/peekcart/global/outbox/OutboxPollingServiceTest.java:39` (P3 범위에서 함께 갱신 — 픽스처 traceId/userId 인자 추가)
- [ ] **P4.** Publisher 2개 (`OrderOutboxEventPublisher`, `PaymentOutboxEventPublisher`) 갱신
  - `saveOutboxEvent(...)` 헬퍼 내부에서 `MDC.get("traceId")` / `MDC.get("userId")` 캡처 후 `OutboxEvent.create(...)` 에 전달
  - **MDC 캡처 헬퍼 신규 — `global/kafka/MdcSnapshot.java`** (정적 헬퍼, `current()` → `record(traceId, userId)` 반환)
    - Publisher 2개 외에도 향후 Outbox 사용처 추가 시 1회 호출로 캡처 가능
    - 단위 테스트 가능 (MDC 미설정 시 둘 다 null 반환 검증)
  - **테스트 케이스**: `OrderOutboxEventPublisherTest` / `PaymentOutboxEventPublisherTest` 에 MDC 미설정 / traceId-only / both 3 케이스 추가
- [ ] **P5.** `OutboxEventRepository` 영향 검토
  - 폴링 쿼리(`findPendingEvents`) 변경 불필요 — `SELECT *` 또는 엔티티 매핑 기반 조회이므로 신규 컬럼 자동 포함
  - JpaRepository 의 derived query 여부만 확인. JPQL 명시적 컬럼 선택이 있다면 trace_id/user_id 추가

### Part C — Producer 헤더 주입

- [ ] **P6.** `OutboxPollingService` 의 `kafkaTemplate.send(...)` 경로를 ProducerRecord 빌드 + 헤더 주입으로 전환
  - 변경 전: `kafkaTemplate.send(event.getEventType(), event.getAggregateId(), event.getPayload()).get()`
  - 변경 후 골격:
    ```java
    ProducerRecord<String, String> record = new ProducerRecord<>(
            event.getEventType(),
            null,
            event.getAggregateId(),
            event.getPayload());
    if (event.getTraceId() != null) {
        record.headers().add(KafkaTraceHeaders.TRACE_ID, event.getTraceId().getBytes(StandardCharsets.UTF_8));
    }
    if (event.getUserId() != null) {
        record.headers().add(KafkaTraceHeaders.USER_ID, event.getUserId().getBytes(StandardCharsets.UTF_8));
    }
    kafkaTemplate.send(record).get();
    ```
  - **헤더 부재 정책**: traceId/userId 가 null 이면 헤더 자체를 추가하지 않음 (빈 문자열 헤더 추가 금지) → `MdcRecordInterceptor.headerValue()` 의 `value.isBlank() ? null` 분기와 정합
  - 기존 retry / failed / Slack 알림 로직 변경 없음
- [ ] **P7.** `OutboxPollingService` 단위 테스트 갱신
  - 기존 테스트 (`OutboxPollingServiceTest` — task-loadtest-session-c P0-A 에서 추가됨, Slack 격리 + MAX_RETRY) 보존
  - 추가 케이스 2건: (a) trace_id/user_id 가 set 된 OutboxEvent 발행 시 ProducerRecord headers 에 두 키 모두 포함, (b) 둘 다 null 인 OutboxEvent 발행 시 headers 가 empty 또는 비포함
  - `KafkaTemplate` mock + `ArgumentCaptor<ProducerRecord<String, String>>` 로 헤더 검증

### Part D — 통합 테스트 (end-to-end 회귀)

> **검증 경로 분리 원칙**: 수동 `KafkaConsumer.poll()` 은 Spring `RecordInterceptor` 를 통과하지 않고, `MdcRecordInterceptor.afterRecord()` (`MdcRecordInterceptor.java:69-74`) 가 처리 후 MDC 를 제거하므로 외부 poll 방식으로 fallback 검증은 불가. 따라서 **(P8a) 원시 헤더 전파** 와 **(P8b) Interceptor fallback 동작** 두 경로로 분리한다.

- [ ] **P8.** 원시 Kafka 헤더 전파 검증 (`OutboxKafkaIntegrationTest` 확장, D-009 `AbstractIntegrationTest` 상속 유지)
  - 별도 consumer-group 의 raw `KafkaConsumer<String, String>` 를 테스트 내에서 구독시켜 ProducerRecord 헤더 자체를 검증
  - **신규 케이스 1**: `MDC.put("traceId", "test-trace-001")` + `MDC.put("userId", "42")` → Order/Payment 도메인 메서드 직접 호출 → 폴링 → raw consumer 가 받은 `ConsumerRecord.headers().lastHeader("X-Trace-Id")` UTF-8 = `"test-trace-001"`, `"X-User-Id"` = `"42"`
  - **신규 케이스 2**: MDC 미설정 상태 → outbox 이벤트 저장 → 폴링 → raw consumer 가 받은 `ConsumerRecord` 에 `X-Trace-Id` / `X-User-Id` 헤더 부재 (lastHeader == null) — Producer 측 빈 헤더 미생성 계약 검증
  - **MDC 누수 방지 (필수)**: 두 케이스가 같은 JVM thread 에서 순차 실행 시 이전 MDC 가 다음 케이스에 누수될 위험 → `OutboxKafkaIntegrationTest` 에 `@BeforeEach` + `@AfterEach` `MDC.clear()` 추가
  - 기존 listener 의 비즈니스 부작용 검증 흐름 (`OutboxKafkaIntegrationTest.java:123-132`) 은 보존, raw consumer 는 추가 부착
- [ ] **P9.** `MdcRecordInterceptor` eventId fallback 회귀 방지 (D-007 계약) — 신규 경계 케이스로 보호 범위 확장
  - **기존 보호** (그대로 유지): `traceId_falls_back_to_eventId` (헤더 부재 → payload.eventId), `traceId_falls_back_to_uuid_when_payload_invalid` (둘 다 없음 → UUID) (`MdcRecordInterceptorTest.java:42-51, 55-65`)
  - **신규 케이스 1**: `X-Trace-Id` 헤더가 blank (빈 문자열) 일 때 payload.eventId 로 fallback — 본 task 의 Producer 측 "null 헤더 미생성" 정책과 정합 검증 (`MdcRecordInterceptor.headerValue()` 의 `value.isBlank() ? null` 분기 보호)
  - **신규 케이스 2**: `X-Trace-Id` 만 헤더에 존재 + `X-User-Id` 부재 → traceId 는 헤더 값, userId 는 null 유지 검증 (인증되지 않은 요청 → outbox user_id NULL 시나리오 회귀 방지)
  - 수단: `MdcRecordInterceptorTest` 단위 테스트 확장. 테스트 전용 `@KafkaListener` 픽스처 방식은 결합도가 높아 보류
- [ ] **P10.** DLQ 경로 헤더 보존 검증 (필수 — 회귀 안전망)
  - Consumer 처리 실패 → DLQ 토픽 메시지에 `X-Trace-Id` / `X-User-Id` 헤더 보존 검증. `DeadLetterPublishingRecoverer` 가 원본 record 헤더를 자동 복사하는 Spring Kafka 기본 동작 (`KafkaConfig.kafkaErrorHandler`, `KafkaConfig.java:76-96`) 에 의존
  - **위치**: 기존 `DlqIntegrationTest` 에 assertion 라인 추가 (신규 테스트 클래스 신설 대신)
  - **테스트 입력 방식 (중요)**: `MDC.put` 만으로는 Kafka 헤더가 자동 생성되지 않으므로 (현재 `DlqIntegrationTest:120` 의 `kafkaTemplate.send(topic, key, value)` 3-인자 경로는 헤더 미주입), 다음 두 방식 중 택1:
    - **(권장) 방식 A**: `ProducerRecord<String, String>` 를 직접 생성해 `KafkaTraceHeaders.TRACE_ID` / `USER_ID` 헤더를 명시적으로 부착 후 `kafkaTemplate.send(record)` 로 의도적 실패 메시지 발행 → DLQ raw consumer 로 두 헤더 보존 검증. 본 task 의 P6 ProducerRecord 전환 패턴 그대로 활용
    - **방식 B**: Outbox publisher 경로 (P3~P6) 로 헤더 주입된 메시지를 만든 뒤 실패 consumer 가 받도록 통합. 더 end-to-end 이지만 setup 복잡도 증가
  - **MDC 누수 방지 (필수)**: `DlqIntegrationTest` 에 `@BeforeEach` + `@AfterEach` `MDC.clear()` 추가 (기존 `setUp` 은 queue/counter 만 초기화 — `DlqIntegrationTest.java:107-111`)
  - ProducerRecord 전환이 본 task 의 핵심이라 DLQ 까지 헤더가 보존되는 것은 회귀 안전망 — §6 완료 조건에도 포함
- [ ] **P11.** 기존 통합 테스트 244건 회귀 (`./gradlew test`) — 모두 통과 유지

### Part E — 문서 동기화

- [ ] **P12.** `docs/TASKS.md`
  - Tech Debt 표 D-010 행: 우선순위 `중간` → `해결됨` 으로 갱신, 비고에 ADR-0008 + Flyway V4 + 테스트 케이스 수 기록
  - "완료된 작업" 표 (L450~) 에 본 task 행 1건 추가 — 날짜·요약·테스트 카운트
- [ ] **P13.** `docs/progress/PHASE3.md`
  - Phase 3 진행 보고서에 본 task 엔트리 신규 추가 (Part A~E 요약, ADR-0008 링크, end-to-end 검증 결과 1줄)
  - "다음 Phase 예정" 절은 불변 (Phase 4 정의는 별도 task)
- [ ] **P14.** `docs/02-architecture.md` 패키지 트리 갱신 (선택)
  - `global/kafka/MdcSnapshot.java` 신규 추가 시 패키지 트리 부분에 한 줄 추가
- [ ] **P15.** `docs/adr/README.md` 인덱스 행 추가 — ADR-0008
- [ ] **P16.** `docs/05-data-design.md` Layer 1 동기화 (What 갱신)
  - outbox_events ERD 절 (L141-153) 에 `trace_id VARCHAR(64) NULL`, `user_id VARCHAR(64) NULL` 컬럼 행 추가
  - 인덱스 전략 절 (L380-389) 은 trace 컬럼용 인덱스 미생성을 현재 상태로 명시. 결정 근거는 `(see ADR-0008)` 으로 위임 (Layer 1 = What, ADR = Why 원칙)
- [ ] **P17.** ADR-0008 Status `Proposed` → `Accepted` 전환 (P1~P16 모두 완료/검증 후, 별도 커밋). 특히 `docs/adr/README.md` (P15) + `docs/05-data-design.md` (P16) + `docs/TASKS.md` (P12) + `docs/progress/PHASE3.md` (P13) Layer 1 동기화가 선행되어야 ADR = Why / Layer 1 = What 원칙과 정합

## 4. 영향 파일

| 파일 | 종류 | 변경 요지 |
|------|------|----------|
| `docs/adr/0008-outbox-trace-context-propagation.md` | 신규 | 결정·대안·트레이드오프 immutable 기록 |
| `docs/adr/README.md` | 수정 | 인덱스 표에 ADR-0008 행 추가 |
| `src/main/resources/db/migration/V4__outbox_trace_context.sql` | 신규 | `outbox_events` 에 `trace_id` / `user_id` 컬럼 추가 (둘 다 NULL 허용) |
| `src/main/java/com/peekcart/global/outbox/OutboxEvent.java` | 수정 | `traceId` / `userId` 필드 추가, `create(...)` 시그니처 확장 |
| `src/main/java/com/peekcart/global/outbox/OutboxPollingService.java` | 수정 | `ProducerRecord` 빌드 + 헤더 주입 |
| `src/main/java/com/peekcart/global/kafka/MdcSnapshot.java` | 신규 | MDC 캡처 정적 헬퍼 (`current() → Snapshot(traceId, userId)`) |
| `src/main/java/com/peekcart/order/infrastructure/outbox/OrderOutboxEventPublisher.java` | 수정 | `saveOutboxEvent` 가 MdcSnapshot 사용해 `OutboxEvent.create` 에 전달 |
| `src/main/java/com/peekcart/payment/infrastructure/outbox/PaymentOutboxEventPublisher.java` | 수정 | 동일 |
| `src/test/java/com/peekcart/global/kafka/MdcSnapshotTest.java` | 신규 | MDC 미설정 / 부분 / 전체 3 케이스 단위 테스트 |
| `src/test/java/.../OutboxPollingServiceTest.java` | 수정 | ProducerRecord 헤더 검증 케이스 2건 추가 (set / null) + 기존 픽스처 `OutboxEvent.create(...)` 호출부 새 시그니처 적용 |
| `src/test/java/.../OrderOutboxEventPublisherTest.java` | 수정 (또는 신규) | MDC 캡처 동작 검증 |
| `src/test/java/.../PaymentOutboxEventPublisherTest.java` | 수정 (또는 신규) | 동일 |
| `src/test/java/com/peekcart/global/kafka/MdcRecordInterceptorTest.java` | 수정 | P9 신규 경계 케이스 2건 (blank X-Trace-Id → eventId fallback, X-User-Id 부재 시 userId null) |
| `src/test/java/com/peekcart/global/kafka/DlqIntegrationTest.java` | 수정 | P10 DLQ 헤더 보존 assertion 추가 + `@BeforeEach`/`@AfterEach` MDC.clear() |
| `src/test/java/.../OutboxKafkaIntegrationTest.java` | 수정 | end-to-end 헤더 전파 케이스 2~3건 추가 |
| `docs/TASKS.md` | 수정 | D-010 행 해결, 완료 작업 표 행 추가 |
| `docs/progress/PHASE3.md` | 수정 | 본 task 엔트리 추가 |
| `docs/02-architecture.md` | 수정 (선택) | `MdcSnapshot.java` 패키지 트리 한 줄 |
| `docs/05-data-design.md` | 수정 | outbox_events ERD 에 trace_id/user_id 컬럼, 인덱스 전략은 미생성 명시 (Why → ADR-0008) |
| `src/main/java/com/peekcart/global/kafka/{MdcRecordInterceptor,KafkaTraceHeaders,MdcPayloadExtractor}.java` | **불변** | D-007 옵션 B 인프라 그대로 활용 (Consumer 측 헤더 우선순위 동작) |
| `src/main/java/com/peekcart/global/filter/MdcFilter.java` | **불변** | HTTP 진입점 MDC 적재 그대로 |
| `src/main/java/com/peekcart/global/config/KafkaConfig.java` | **불변** | DLQ recoverer 가 헤더 자동 복사 |
| `k8s/**`, `loadtest/**` | **불변** | 본 task 는 코드/문서 범위 |

## 5. 검증 방법

- **P2 Flyway 무결성**
  - 현 build.gradle 은 Flyway 라이브러리만 의존하고 Gradle Flyway 플러그인 미적용 (`build.gradle:1-6, 66-68`) → `flywayValidate` 태스크 부재
  - 대체 검증: `./gradlew test --tests '*OutboxKafkaIntegrationTest'` (Spring Boot 기동 → Flyway 자동 적용 + JPA `validate` 동시 검증, V4 까지 적용된 Testcontainers MySQL 에서 신규 컬럼 매핑 확인)
  - 기존 outbox_events 행이 있는 환경 시뮬레이션: V3 까지 적용된 DB 에 V4 적용 → 기존 행 trace_id/user_id NULL 확인 (`SELECT id, trace_id, user_id FROM outbox_events;`)
- **P4 Publisher MDC 캡처**
  - 단위 테스트: MDC 미설정 → 엔티티 traceId/userId null 검증, MDC 설정 → 동일 값 영속 검증
  - 캡처 시점이 폴링 스레드가 아닌 호출 스레드(@Transactional Order/Payment 서비스 메서드 호출 스레드)임을 명시적으로 테스트 (별도 스레드에서 호출 시 null 반환)
- **P6 ProducerRecord 헤더 주입**
  - `OutboxPollingServiceTest` — `ArgumentCaptor<ProducerRecord<String, String>>` 로 capture
  - assertion: `record.headers().lastHeader("X-Trace-Id").value()` UTF-8 디코딩 결과 == OutboxEvent.traceId, userId 동일
  - null 케이스: `record.headers().lastHeader("X-Trace-Id") == null` (빈 문자열 헤더 미생성 검증)
- **P8a 원시 헤더 전파** (D-010 신규 계약, raw KafkaConsumer 경로)
  - 케이스 1: MDC.put → 도메인 메서드 호출 → 폴링 트리거 → raw consumer-group 가 받은 ConsumerRecord 헤더 `X-Trace-Id` 일치 (test-trace-001), `X-User-Id` 일치 (42)
  - 케이스 2: MDC 미설정 → ConsumerRecord 헤더 부재 (lastHeader == null) — Producer 측 빈 헤더 미생성 계약
- **P8b Interceptor fallback** (D-007 회귀 방지, MdcRecordInterceptorTest 단위 확장)
  - 헤더 부재 ConsumerRecord 입력 시 MDC traceId 가 eventId payload 로 채워짐 verify
- **D-001 패턴 안전망**: 통합 테스트 setUp 에서 `MDC.clear()` 호출하여 테스트 간 MDC 누수 방지
- **P9 회귀**
  - `./gradlew test` 전체 244건 + 신규 추가분 모두 통과
  - 특히 `IdempotencyIntegrationTest`, `DlqIntegrationTest` 통과 — Producer 경로 변경이 멱등성/DLQ 계약을 깨지 않음
- **로그 육안 검증** (선택)
  - 로컬 docker-compose + 앱 기동 → `POST /api/v1/orders` (인증된 요청) → 앱 로그에서 동일 traceId 가 HTTP 요청 → Outbox 저장 → 폴링 publish → Consumer 수신 → DLQ (의도적 실패 시) 모든 라인에 일관되게 출력되는지 1회 스모크
- **schema 회귀**
  - V4 적용 후 `DESC outbox_events;` 에 trace_id / user_id 컬럼 존재 + nullable 확인
  - 기존 인덱스 (`idx_outbox_status_created` 등, `V2__outbox_processed_events.sql:17`) 변경 없음

## 6. 완료 조건

- ADR-0008 작성 완료, Status `Accepted` 로 전환됨 (P17)
- `docs/adr/README.md` 인덱스에 ADR-0008 행 추가
- `docs/05-data-design.md` outbox_events ERD 에 trace_id/user_id 컬럼 반영 + 인덱스 미생성 명시 (P16)
- Flyway V4 마이그레이션이 로컬/CI 환경 모두에서 정상 적용 (`./gradlew test --tests '*OutboxKafkaIntegrationTest'` 로 검증)
- `OutboxEvent` 가 traceId/userId 컬럼 보유, `create(...)` 시그니처가 명시 인자 받음, 모든 호출부 (production 2 + 테스트 픽스처 1) 갱신 완료
- `OutboxPollingService` 가 ProducerRecord 경로로 전환, traceId/userId 헤더 주입
- `MdcSnapshot` 헬퍼 추가 + 단위 테스트 통과
- `OrderOutboxEventPublisherTest` / `PaymentOutboxEventPublisherTest` MDC 캡처 검증 (미설정 / traceId-only / both 3 케이스) 통과 — HTTP 요청 스레드에서 Outbox 저장 시점 캡처 계약 검증
- `OutboxPollingServiceTest` 에 ProducerRecord 헤더 검증 2 케이스 (set / null) 추가
- `OutboxKafkaIntegrationTest` 에 raw consumer 헤더 전파 케이스 2건 (P8: MDC set / MDC clear) 추가 + `@BeforeEach`/`@AfterEach` MDC.clear() 안전망
- `MdcRecordInterceptorTest` 에 신규 경계 케이스 추가 (P9, blank X-Trace-Id fallback + X-User-Id 부재)
- `DlqIntegrationTest` 에 DLQ 헤더 보존 assertion 추가 (P10, X-Trace-Id / X-User-Id) + `@BeforeEach`/`@AfterEach` MDC.clear() 안전망
- `./gradlew test` 전체 통과 (244 + 신규 추가분), 5xx/회귀 0건
- `docs/TASKS.md` D-010 행 `해결됨` 으로 갱신, 완료 작업 표 행 추가
- `docs/progress/PHASE3.md` 본 task 엔트리 존재
- 로컬 스모크 (선택) — 동일 traceId 가 HTTP 요청 → Outbox → Consumer 로그에 일관되게 출력

## 7. 트레이드오프 / 비대상

- **OpenTelemetry / W3C TraceContext 미도입** — 모놀리스 단계에서 Outbox-only 가시성 확보가 우선. Phase 4 MSA 분리 + micrometer-tracing 도입 시 별도 ADR 로 W3C 표준 도입. 현재 `MdcRecordInterceptor` 헤더 우선순위가 forward-compatible 하므로 그 시점에 `traceparent` 한 단계만 추가하면 동작.
- **payload envelope 변경 없음** — `KafkaEventEnvelope` 스키마 불변. trace 컬럼은 envelope 외부(엔티티 컬럼 + Kafka 헤더)에만 존재. 기존 메시지 호환성 유지 + Consumer payload 파서 변경 불필요.
- **trace 별도 테이블 미사용** — 단일 테이블 컬럼 추가가 가시성 가치 대비 운영 단순. 분석 빈도가 낮은 컬럼이라 인덱스도 미생성.
- **인덱스 미생성** — trace 기반 조회는 사후 ad-hoc 분석용. row insert/update 비용 회피 우선. 빈도가 높아지면 후속 ADR 로 분리.
- **OutboxEvent ↔ MDC 결합 방향**: 옵션 (a) 명시 인자 채택. 옵션 (b) 도메인 엔티티 내부 `MDC.get()` 직접 호출은 (i) 도메인 횡단 엔티티의 SLF4J 결합, (ii) 단위 테스트 시 MDC 명시 주입 필요한 점에서 기각.
- **Backfill 없음** — 기존 outbox_events 행은 trace/user_id NULL 유지. 의도적 — 과거 이벤트의 trace 복원은 불가능하고, 현재 시점 이후 이벤트만 가시성 확보로 충분.
- **DLQ 경로 헤더 검증**: P10 으로 **필수** 확정 — 기존 `DlqIntegrationTest` 에 assertion 추가 (신규 테스트 클래스 신설 대신). `DeadLetterPublishingRecoverer` Spring Kafka 기본 동작 (`KafkaConfig.kafkaErrorHandler:76-96`) 에 의존. 테스트 입력은 ProducerRecord 직접 생성 + KafkaTraceHeaders 부착 방식 권장 (loop 2 / loop 3 결정).
- **Phase 4 차단 아님** — Phase 4 진입을 막진 않지만, MSA 진입 후 동일 작업을 하면 cross-service 까지 연계되어 작업 범위가 커진다. 모놀리스 시점에 처리하는 것이 비용 효율.
- **기존 모놀리스 단일 컨텍스트 한정** — 본 task 는 producer-consumer 가 같은 JVM 인 모놀리스 가정에서 동작. Phase 4 분리 시점에 cross-service trace 표준 도입과 함께 재검토.

## 8. 커밋 / 브랜치 전략

**브랜치**: `feat/d010-outbox-trace-context`

**커밋 구조 (5분할, 안전한 revert 단위)**

1. `docs(adr): add ADR-0008 outbox trace context propagation (Proposed)` — Part A (ADR 본문 + README 인덱스 행)
2. `feat(outbox): persist trace context columns on outbox events` — Part B (V4 Flyway + OutboxEvent 필드 + Publisher MDC 캡처 + MdcSnapshot + 단위 테스트)
3. `feat(outbox): inject trace headers on kafka publish` — Part C (OutboxPollingService ProducerRecord 전환 + 단위 테스트)
4. `test(outbox): verify end-to-end trace header propagation` — Part D (P8a raw consumer 헤더 검증 + P8b MdcRecordInterceptorTest fallback 케이스 + P8c 선택)
5. `docs: mark D-010 resolved + ADR-0008 accepted` — Part E (TASKS.md / PHASE3.md / 02-architecture.md / 05-data-design.md 동기화 + ADR-0008 Status Proposed → Accepted Status 전환)

**Revert 시나리오**

- commit 5 단독 revert → 문서 롤백, 코드는 정상. ADR-0008 만 Proposed 로 회귀 (안전)
- commit 4 단독 revert → 통합 테스트만 제거, 단위 테스트 보호막 유지 (위험도 낮음)
- commit 3 단독 revert → ProducerRecord 전환 롤백. **단, commit 2 의 trace 컬럼은 채워지지만 publish 시 헤더 미주입** → 일시적 부분 작동 상태. 의도적 revert 시 commit 5 → 4 → 3 → 2 → 1 순서 권장
- commit 2 단독 revert → V4 Flyway 가 적용된 환경에서는 컬럼이 남아 있으나 엔티티가 인식 못함 → **DB 와 코드 불일치 위험**. revert 보다 forward fix(V5 down migration) 가 안전
- commit 1 단독 revert → ADR 만 제거. 코드 변경 그대로 → ADR 누락 + 결정 추적 불가 (불권장)

## 9. 리뷰 이력

> 본 계획서는 작성 직후 `/plan` 리뷰 루프 / `/work` 진행 중 GW-2 리뷰를 거치며 본 절에 추가됩니다.

### 2026-05-01 09:00 — GP-2 (loop 1)
- 리뷰 항목: 6건 (P0:0, P1:2, P2:4)
- 사용자 선택: [2] 전체 반영
- 반영 사항:
  - **id 1 (P1, test)** P8 분리 — P8 (raw KafkaConsumer 로 헤더 전파 검증) + P9 (MdcRecordInterceptorTest 단위 fallback 검증) + P10 (DLQ 선택). 외부 poll 이 RecordInterceptor 를 통과하지 못하는 한계 명시
  - **id 2 (P1, doc)** Part E 에 신규 항목 (P16 = `docs/05-data-design.md` outbox_events ERD/인덱스 전략에 trace_id/user_id 반영, 인덱스 미생성은 ADR-0008 참조)
  - **id 3 (P2, test)** P2 검증 명령 `flywayValidate` → `./gradlew test --tests '*OutboxKafkaIntegrationTest'` 로 교체 (Gradle Flyway plugin 미적용 사실 반영)
  - **id 4 (P2, doc)** 폴링 쿼리 `status + id` → `status='PENDING' ORDER BY created_at ASC` 로 정정, 인덱스명 `idx_outbox_status_created` 로 통일
  - **id 5 (P2, test)** §6 완료 조건에 Publisher MDC 캡처 테스트 (Order/Payment 3 케이스) 통과 명시
  - **id 6 (P2, bug)** P3 에 `OutboxEvent.create` 호출부 전수 갱신 항목 추가 — 식별된 호출부 3곳 (production 2 + 테스트 픽스처 1) 명시
- 최종 항목 번호 (loop 1 직후): P12=TASKS.md, P13=PHASE3.md, P14=02-architecture.md, P15=adr/README.md, P16=05-data-design.md, P17=ADR Accepted
- raw: .cache/codex-reviews/plan-task-d010-outbox-trace-context-1777593490.json
- run_id: plan:20260430T235810Z:d730fe60-5259-4564-8bfb-76dcd493d546:1

### 2026-05-01 09:13 — GP-2 (loop 2)
- 리뷰 항목: 4건 (P0:0, P1:2, P2:2)
- 사용자 선택: [2] 전체 반영
- 반영 사항:
  - **id 1 (P1, doc)** P17 (ADR-0008 Accepted) 전환 조건 `P1~P11` → `P1~P16 모두 완료/검증 후`. README/05-data-design/TASKS/PHASE3 동기화 선행 명시 — Layer 1 = What / ADR = Why 원칙과 정합
  - **id 2 (P1, test)** P10 (DLQ 헤더 보존) 을 선택 → **필수** 로 격상. `DlqIntegrationTest` 에 X-Trace-Id / X-User-Id 보존 assertion 추가, §6 완료 조건에 포함. ProducerRecord 전환 회귀 안전망에 포함
  - **id 3 (P2, test)** P9 의 단순 중복 회피 — 기존 `traceId_falls_back_to_eventId` 보존. 신규 케이스 2건으로 보호 범위 확장: (a) blank X-Trace-Id → eventId fallback, (b) X-Trace-Id 만 / X-User-Id 부재 → userId null
  - **id 4 (P2, doc)** 본 §9 loop 1 엔트리의 P14/P15 표현 → 최종 번호 (P16=05-data-design, P17=ADR Accepted) 로 정정
- raw: .cache/codex-reviews/plan-task-d010-outbox-trace-context-1777594230.json
- run_id: plan:20260501T001030Z:2c536ce9-c975-47a8-a3f3-03efae4c5732:2

### 2026-05-01 09:21 — GP-2 (loop 3, attempts 3/3 상한)
- 리뷰 항목: 4건 (P0:0, P1:1, P2:3)
- 사용자 선택: [2] 전체 반영
- 반영 사항:
  - **id 1 (P1, test)** P10 검증 입력 방식 명시 — `MDC.put` 만으로는 Kafka 헤더 미생성. 권장 방식 A (ProducerRecord 직접 생성 + KafkaTraceHeaders 부착) / 방식 B (Outbox publisher 경로) 택1, 권장은 A. 현재 `DlqIntegrationTest:120` 의 3-인자 send 경로 한계 명시
  - **id 2 (P2, doc)** §4 영향 파일 표에 `MdcRecordInterceptorTest.java` (P9), `DlqIntegrationTest.java` (P10) 추가 — 작업 항목 ↔ 영향 파일 ↔ 완료 조건 같은 파일 집합으로 통일
  - **id 3 (P2, doc)** §7 트레이드오프의 DLQ 보류 표현 제거 — "P10 으로 필수 확정, DlqIntegrationTest 에 assertion 추가, ProducerRecord 직접 생성 방식 권장" 으로 정리
  - **id 4 (P2, test)** P8/P10 작업 항목 + §6 완료 조건에 `@BeforeEach`/`@AfterEach` `MDC.clear()` 안전망 명시 — JVM thread 재사용 시 이전 케이스 MDC 누수 방지
- raw: .cache/codex-reviews/plan-task-d010-outbox-trace-context-1777594809.json
- run_id: plan:20260501T002009Z:2202121e-52f3-4a33-bbb0-d4c065adaf70:3

### 2026-05-01 22:50 — GW-2 (work loop 1, split c1..c3)
- 리뷰 run:
  - c1: work:20260501T131535Z:8b45dc3f-9978-4ad8-9dbf-a749a6d1cac4:1:c1 (379 lines, 6 files)
  - c2: work:20260501T131535Z:8b45dc3f-9978-4ad8-9dbf-a749a6d1cac4:2:c2 (584 lines, 8 files)
  - c3: work:20260501T131535Z:8b45dc3f-9978-4ad8-9dbf-a749a6d1cac4:3:c3 (353 lines, 6 files)
- aggregate_result: ok
- 항목: 5건 (P0:0, P1:2, P2:3)
- 사용자 선택: c1:1 거부 + 나머지 4건 반영
- 반영 사항:
  - **c1:2 (P2, test)** Order/Payment OutboxEventPublisherTest `setUp` 에 MDC.clear() 추가 (기존 @AfterEach 만 있음 → JVM thread 재사용 누수 방지)
  - **c2:1 (P1, test)** `OutboxKafkaIntegrationTest` 의 `peek()` first record 검증을 key (=order aggregateId) 필터링으로 변경 — 클래스 내 다른 테스트 (orderCancelled_e2e 등) 가 누적시키는 stale record 회피. `awaitRecordWithKey(...)` 헬퍼 도입
  - **c3:1 (P2, bug)** `OutboxPollingService.buildRecord` 의 헤더 부재 정책을 null → blank 까지 확장 (`addHeaderIfPresent` 헬퍼). ADR-0008 / P6 의 "blank 도 미주입" 계약 보호. `OutboxPollingServiceTest.producerRecordOmitsTraceHeadersWhenBlank` 케이스 신규 추가
  - **c3:2 (P2, doc)** ADR-0008 Decision 절의 `Snapshot.current()` → `MdcSnapshot.current()` 표기 통일 (Consequences 절 표기와 일치)
- 거부 사유:
  - **c1:1 (P1, doc)** PHASE3.md 가 chunk 1 범위 외 작업까지 완료 선언 → split 인공물. PHASE3 는 task 전체 요약 문서이고 실제 변경은 모든 chunk 에 존재 (TASKS.md, 통합/단위 테스트 등은 c2/c3 에 위치). 거부.
- diff: .cache/diffs/diff-task-d010-outbox-trace-context-1777641273.patch (1316 lines, 20 files)
- raw: .cache/codex-reviews/diff-task-d010-outbox-trace-context-1777641273-c{1,2,3}.json
- 회귀: ./gradlew test 265건 전부 통과 (244 기존 + 신규 21)

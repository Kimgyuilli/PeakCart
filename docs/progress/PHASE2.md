# Phase 2 진행 보고서 — 성능 개선

> Phase 2 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md` 참고

---

## Phase 2 목표

**Exit Criteria**:
- [x] Redis 캐싱 적용 후 통합 테스트에서 캐시 적중/무효화 동작 확인
- [x] 동시 주문 테스트 시 오버셀링 0건
- [x] Outbox → Kafka 이벤트 발행 정상 동작
- [ ] DLQ 토픽으로 실패 메시지 라우팅 확인

---

## 작업 이력

### 2026-03-28

#### Phase 2 Task 정의

**완료 항목**:
- `docs/TASKS.md`에 Phase 2 Task 6개 정의 (Task 2-1 ~ 2-6)
- `docs/progress/PHASE2.md` 생성

**Task 구성**:
1. Task 2-1: Redis 캐싱 (Cache Aside 패턴)
2. Task 2-2: Redis 분산 락 (Redisson + DB 낙관적 락 이중 방어)
3. Task 2-3: Kafka + Outbox 도입 (이벤트 유실 방지)
4. Task 2-4: Consumer 멱등성 (processed_events 중복 체크)
5. Task 2-5: DLQ 구성 (재시도 정책 + DLQ 라우팅)
6. Task 2-6: ShedLock (스케줄러 중복 실행 방지)

#### 설계 문서 리뷰 + 수정

설계서 전체 교차 검증 수행. 아래 항목 수정 완료:

**설계서 수정 (3건)**:
- `05-data-design.md`: `processed_events` UK → `(event_id, consumer_group)` 복합 UK로 변경 (Phase 2 단일 DB에서 다중 consumer group 지원)
- `07-roadmap-portfolio.md`: Phase 2 Exit Criteria에서 JMeter TPS 측정 항목 제거 (Phase 3 범위), 캐시 적중/무효화 검증으로 대체
- `02-architecture.md`: Phase 2 패키지 구조 — `OutboxEventPublisher` 주석 수정, `OutboxPollingScheduler`(`global/scheduler/`) 추가, `ProductCacheRepository` 제거 후 `CacheConfig` 추가, `idempotency/`를 `global/`로 이동

**TASKS.md 수정 (4건)**:
- Task 2-3: KRaft + Zookeeper → KRaft만, 이벤트 페이로드 DTO 항목 추가, Outbox FAILED Slack 알림 항목 추가, Phase 2 재고 복구 경로 명시 (모놀리스 직접 호출 유지)
- Task 2-4: `processed_events` 복합 UK 반영, 패키지 위치 `global/idempotency/` 명시
- Exit Criteria 로드맵과 동기화

**주요 결정**:
- Phase 2 `order.cancelled` 재고 복구: 모놀리스이므로 `cancelOrder()` 내 직접 호출 유지, Kafka는 Notification만 소비. Product 도메인 Kafka Consumer 분리는 Phase 4에서 수행
- 멱등성 패키지 위치: `global/idempotency/` (횡단 관심사, Phase 4에서 `common/idempotency/`로 이동)

#### 멀티에이전트 설계 리뷰 + Devil's Advocate 토론

4개 전문 에이전트(Architecture, Kafka, Data Design, Redis) 리뷰 → 비판(Critic) 2개 → 방어(Defender) 2개, 총 3라운드 토론 수행.

**토론 결과**: 원래 12건(P0 4건 + P1 8건) → 6건 반영, 6건 삭제 (과잉 설계 / 프레임워크 기본 동작 / 튜닝 파라미터)

**반영 6건**:

1. **Outbox 패키지 구조 정리** (`02-architecture.md` 수정):
   - `OutboxEvent` + `OutboxEventRepository` + `OutboxPollingScheduler` → `global/outbox/`로 통합 (횡단 관심사, 단일 테이블)
   - `OutboxEventPublisher`는 도메인별 유지 (`OrderOutboxEventPublisher`, `PaymentOutboxEventPublisher`)
   - 근거: Entity+Repository가 특정 도메인에 귀속되면 다른 도메인이 참조 시 크로스 도메인 결합 발생. Publisher는 호출 주체가 도메인이므로 도메인별 배치가 가독성에 유리

2. **상품 캐시에서 재고 제외** (Task 2-1 비고):
   - 재고는 차감/복구마다 변경되어 캐시 무효화 빈번, PK 단건 조회(~1ms)로 충분
   - 대안(재고 포함 + TTL 30초)의 트레이드오프도 인지 — 면접 대비

3. **Redis 장애 fallback 방향성** (Task 2-2 비고):
   - try-catch fallback → 락 없이 진행, `@Version`이 최후 방어선. 낙관적 락 충돌률 증가 감수

4. **데드락 방지** (Task 2-2 비고):
   - 다중 상품 주문 시 productId 오름차순 정렬 후 순차 락 획득

5. **Kafka DTO 전환 전략** (Task 2-3 비고):
   - Phase 1 domain/event/ record는 Spring Event용 유지, Kafka DTO는 8-2 래핑 구조로 별도 정의

6. **이벤트 소비 경로 완전성** (Task 2-3 주석 확장):
   - 4개 토픽별 Consumer 매핑을 명시적으로 나열 (payment.failed → OrderEventConsumer 경로 포함)

**삭제 6건 + 근거**:
- P0-2 auto-commit 비활성화: Spring Kafka가 `@KafkaListener` 사용 시 자동 처리
- P1-1 Publisher 위치: Outbox 패키지 정리에 흡수
- P1-3 retry_count 최대값: 튜닝 파라미터, 코드 상수로 충분
- P1-5 DLQ Consumer 방식: `DeadLetterPublishingRecoverer` recoverer 콜백으로 충분
- P1-6 waitTime/leaseTime 구체값: 설계서에 확정하면 문서-코드 불일치 유발, application.yml 프로퍼티로 관리
- P1-8 lockAtLeastFor: Phase 2 단일 인스턴스에서 검증 불가, Phase 3으로 이관

### 2026-03-29

#### Task 2-1: Redis 캐싱 구현 (통합 테스트 제외)

**완료 항목**:
- `build.gradle`에 `spring-boot-starter-cache` 의존성 추가
- `CacheConfig` 생성 (`@EnableCaching`, `RedisCacheManager`, JSON 직렬화, 캐시별 TTL)
- `CachedPage` 래퍼 record 생성 (`global/cache/`) — `PageImpl` Redis 직렬화 문제 해결
- `ProductInfoDto` (재고 제외 캐싱용), `ProductListDto` (목록 캐싱용) DTO 추가
- `ProductCacheService` 생성 — `@Cacheable` 메서드 분리 (AOP 프록시 우회 방지)
- `ProductQueryService` 수정 — `ProductCacheService` 위임, 재고는 DB 실시간 조회
- `ProductCommandService` 수정 — `@CacheEvict`/`@Caching` 적용 (create/update/delete)
- `ProductResponse` 수정 — `from(Product)` → `from(ProductListDto)`
- 기존 단위 테스트 수정 (ProductQueryServiceTest, ProductControllerTest, ProductFixture)

**미완료**: 통합 테스트 (Testcontainers Redis 캐시 적중/무효화 검증)

**주요 결정**:
- **ProductCacheService 별도 빈 분리**: 동일 빈 내 `@Cacheable` 내부 호출은 AOP 프록시를 타지 않아 캐시 미동작. self-injection 대신 별도 서비스로 분리하여 해결
- **CachedPage 래퍼 도입**: Spring `PageImpl`은 Jackson 기본 역직렬화 불가. `CachedPage` record로 래핑하여 Redis 직렬화 안정성 확보
- **목록 캐시 무효화 전략**: 페이징 파라미터별 개별 evict 불가 → `allEntries = true` 채택. 상품 변경은 관리자 저빈도 작업이므로 전체 flush 수용

#### Task 2-1: 코드 리뷰 개선 (4건)

설계서 대조 + 코드 리뷰 수행 후 아래 항목 개선 완료:

**P0 — CacheConfig PolymorphicTypeValidator 보안 강화**:
- `LaissezFaireSubTypeValidator`(모든 타입 허용) → `BasicPolymorphicTypeValidator`로 교체
- `com.peekcart.` + `java.lang.` + `java.util.` 패키지만 역직렬화 허용, Deserialization Gadget 공격 벡터 차단
- `NON_FINAL` → `EVERYTHING` 변경: Java record는 암묵적 final이므로 NON_FINAL에서 `@class` 타입 정보 누락 → 역직렬화 실패. 통합 테스트에서 발견하여 수정
- 불필요한 ObjectMapper 이중 생성 제거

**P1 — CacheConfig defaultConfig TTL 중복 제거**:
- `defaultConfig`에 기본 TTL 10분 직접 설정, `cacheDefaults()`에서 `.entryTtl()` 재호출 제거
- `productListConfig` 변수 제거 — `defaultConfig`와 동일하므로 직접 참조

**P1 — 목록 캐시 키 Sort 미포함 의도 명시**:
- `ProductCacheService.getProductList()` 캐시 키에 Sort 조건 미포함 사유 주석 추가
- Controller에서 `@PageableDefault` 고정 정렬만 사용하므로 현재 문제없으나, 향후 정렬 옵션 추가 시 키 수정 필요

**P2 — CachedPage 간결화**:
- `page.getPageable().getPageSize()` → `page.getSize()` (동일 동작, 더 간결)

#### Task 2-1: 통합 테스트 완료 (5건)

`ProductCacheIntegrationTest` 작성 — Testcontainers Redis + MySQL 기반 캐시 적중/무효화 검증:

**테스트 항목**:
1. 상세 조회 캐시 적중: 첫 호출 캐시 미스 → 두 번째 호출 캐시 적중 확인
2. 목록 조회 캐시 적중: 동일 패턴 검증
3. 상품 수정 시 캐시 무효화: 상세 + 목록 캐시 모두 evict 확인, 수정된 데이터 반환 검증
4. 상품 삭제 시 캐시 무효화: 상세 + 목록 캐시 모두 evict 확인
5. 상품 등록 시 목록 캐시 무효화: 목록 캐시 evict + 새 상품 포함 확인

**검증 방식**: `CacheManager.getCache().get(key)` — null 여부로 캐시 상태 직접 확인

### 2026-03-30

#### Task 2-2: Redis 분산 락 구현 (통합 테스트 제외)

**완료 항목**:
- `build.gradle`에 `org.redisson:redisson:3.27.0` 의존성 추가
- `RedissonConfig` 생성 (`RedisConnectionDetails` 주입, Testcontainers `@ServiceConnection` 호환)
- `DistributedLockManager` 생성 (`global/lock/`) — `tryLock`/`unlock`, Redis 장애 시 fallback(`true` 반환)
- `InventoryService.decreaseStock()` 수정 — 분산 락 적용 (키: `inventory-lock:{productId}`, waitTime 3s, leaseTime 5s)
- `ProductPortAdapter` 수정 — `InventoryRepository` 직접 접근 → `InventoryService` 위임으로 변경
- `InventoryServiceTest` 수정 — `DistributedLockManager` mock 추가, 락 획득 실패 테스트 추가
- `InventoryConcurrencyTest` 수정 — Redis Testcontainer 추가 (RedissonConfig 빈 생성 호환)
- 기존 전체 테스트 219건 통과 확인

**미완료**: 동시성 통합 테스트 (50스레드 오버셀링 검증, Redis 장애 fallback)

**주요 결정**:
- **RedissonConfig에 `RedisConnectionDetails` 사용**: `@Value("${spring.data.redis.host}")` 대신 Spring Boot의 `RedisConnectionDetails` 빈 주입. `@ServiceConnection`은 프로퍼티가 아닌 `ConnectionDetails` 빈으로 연결 정보를 제공하므로, `@Value`로는 Testcontainer 포트를 받을 수 없음
- **ProductPortAdapter → InventoryService 위임**: 기존에는 `ProductPortAdapter`가 `InventoryRepository`에 직접 접근하여 `inventory.decrease()` 호출. 분산 락이 `InventoryService`에 적용되므로, 모든 재고 변경이 락을 통과하도록 `InventoryService` 위임으로 변경
- **데드락 방지 불필요**: 각 `decreaseStock()` 호출이 개별 lock/unlock (동시에 2개 이상 락 미보유) → 순환 대기 불가. TASKS.md의 "global ordering" 설계는 모든 락을 동시 보유하는 경우를 전제하나, 현재 구현은 개별 lock/unlock이므로 불필요
- **restoreStock()은 락 미적용**: 재고 복구는 증가 연산이므로 오버셀링 위험 없음

#### Task 2-2: 동시성 통합 테스트 완료

`InventoryConcurrencyTest`에 분산 락 동시성 테스트 추가:

- 50스레드 동시 `InventoryLockFacade.decreaseStock()` 호출
- 분산 락이 순차 실행을 보장하여 50건 전부 성공
- 최종 재고 = 초기(100) - 50 = 50, 오버셀링 0건 확인
- 기존 낙관적 락 테스트(10스레드)와 함께 2건의 동시성 테스트 보유

**Phase 2 Exit Criteria 달성**: 동시 주문 테스트 시 오버셀링 0건 ✅

### 2026-03-31

#### Task 2-3: Kafka + Outbox 구현 (통합 테스트 제외)

**완료 항목**:
- `docker-compose.yml`에 Kafka 서비스 추가 (apache/kafka:3.8.1, KRaft 모드)
- `build.gradle`에 spring-kafka + spring-kafka-test + testcontainers:kafka 의존성 추가
- Flyway `V2__outbox_processed_events.sql` 생성 (outbox_events + processed_events 테이블)
- `OutboxEvent` Entity + `OutboxEventStatus` Enum (`global/outbox/`)
- `OutboxEventRepository` 계층 (인터페이스 + JPA + Impl)
- Kafka 이벤트 페이로드 DTO 정의 (`KafkaEventEnvelope` 래핑 구조 + 4개 Payload record)
- `OrderOutboxEventPublisher` / `PaymentOutboxEventPublisher` (도메인별 `infrastructure/outbox/`)
- `OrderCommandService`, `PaymentCommandService`에서 `ApplicationEventPublisher` → `OutboxEventPublisher` 전환
- `KafkaConfig` 생성 (4개 NewTopic Bean, Producer/Consumer 설정)
- `OutboxPollingScheduler` 생성 (5초 폴링, MAX_RETRY=5, Slack 알림)
- Kafka Consumer 3개 생성 (`PaymentEventConsumer`, `OrderEventConsumer`, `NotificationConsumer`)
- 기존 `@TransactionalEventListener` 3개 비활성화 (`@Component` 제거)
- 기존 단위 테스트 수정 (OrderCommandServiceTest, PaymentCommandServiceTest) — 44개 전부 통과
- `application.yml`에 Kafka producer/consumer 설정 추가, `application-local.yml`에 bootstrap-servers 추가

**미완료**: 통합 테스트 (Testcontainers Kafka + MySQL + Redis, E2E 플로우 검증)

**주요 결정**:
- **Payment Kafka payload에 userId 추가**: Payment 엔티티에 userId가 없으나, NotificationConsumer가 알림 생성 시 userId 필요. OrderPort를 통한 조회 대신 Publisher에서 userId를 직접 전달받아 payload에 포함
- **Payment payload에서 orderNumber 제외**: Payment 엔티티에 orderNumber가 없어 포함 불가. 설계 문서의 이상적 스키마와 현재 구현 사이의 차이 인지
- **기존 EventListener 비활성화 방식**: `@Component` 제거로 빈 등록 해제. 파일은 보존하여 Phase 1 로직 참조 가능

#### Task 2-3: 코드 리뷰 개선 (3건)

설계서 전체 대조 + 코드 리뷰 수행 후 아래 항목 개선 완료:

**P0 — eventId 이중 생성 수정**:
- `OrderOutboxEventPublisher`, `PaymentOutboxEventPublisher`에서 `KafkaEventEnvelope`의 `eventId`와 `OutboxEvent`의 `eventId`가 서로 다른 UUID로 생성되던 문제 수정
- `OutboxEvent`를 먼저 생성하고, 그 `eventId`를 `KafkaEventEnvelope`에 전달하도록 순서 변경
- `OutboxEvent`에 `updatePayload()` 메서드 추가
- Task 2-4 멱등성 처리에서 `event_id` 기반 중복 체크 시 Kafka 메시지와 DB의 eventId 정합성 보장

**P1 — Consumer `extractPayload()` NPE 방어**:
- `OrderEventConsumer`, `PaymentEventConsumer`, `NotificationConsumer` 3개 Consumer에서 `get("payload")`가 `null`일 때 명시적 `IllegalArgumentException` 발생으로 변경
- NPE 대신 원인을 특정할 수 있는 에러 메시지 제공

**설계 문서 동기화 (2건)**:
- `02-architecture.md`: Phase 2 패키지 구조에서 `OrderEventProducer`, `PaymentEventProducer` 제거 (OutboxPollingScheduler에 통합되어 불필요)
- `04-design-deep-dive.md`: `order.created` payload에서 `productName` 제거 (OrderItem에 없는 필드), `payment.completed` payload에서 `orderNumber` → `userId` 반영 (실제 구현과 일치)

#### Task 2-3: 2차 코드 리뷰 개선 (3건)

설계서 전체 대조 + 코드 리뷰 수행 후 아래 항목 개선 완료:

**P0 — OutboxPollingScheduler → SlackPort 크로스 도메인 의존 해결**:
- `OutboxPollingScheduler`(`global/outbox/`)가 `notification/application/port/SlackPort`를 직접 참조 — global이 특정 도메인에 의존하는 아키텍처 위반
- `SlackPort` 인터페이스를 `global/port/SlackPort`로 이동 (횡단 관심사)
- `SlackNotificationClient`, `NotificationCommandService`, `NotificationCommandServiceTest` import 일괄 수정
- 기존 `notification/application/port/` 빈 디렉토리 삭제

**P1 — OutboxEvent 팩토리 빈 payload 임시 상태 제거**:
- 기존: `OutboxEvent.create(..., "")` → `updatePayload()` 2단계 호출 — 엔티티가 `payload=""`인 invalid 상태로 잠시 존재
- 변경: `OutboxEvent.create(..., Function<String, String> payloadFactory)` — eventId 생성 후 팩토리 함수로 payload 즉시 주입
- `updatePayload()` public 메서드 제거, `OrderOutboxEventPublisher`/`PaymentOutboxEventPublisher` 수정

**P1 — 비활성화된 EventListener 미사용 import 제거**:
- `OrderEventListener`, `PaymentEventListener`, `NotificationEventListener` 3개 파일에서 `import org.springframework.stereotype.Component` 제거

전체 222건 테스트 통과 확인.

### 2026-04-01

#### Task 2-3: 통합 테스트 완료 (5건) — Task 2-3 완료

`OutboxKafkaIntegrationTest` 작성 — Testcontainers Kafka + MySQL + Redis 기반 E2E 플로우 검증:

**완료 항목**:
- `build.gradle`에 `org.awaitility:awaitility` 테스트 의존성 추가
- `OutboxKafkaIntegrationTest` 생성 (`global/outbox/`)
- 3개 Testcontainer 구성: MySQL 8.0 + Redis 7 + Kafka (apache/kafka:3.8.1)
- SlackPort no-op stub (`@TestConfiguration`)
- Awaitility 비동기 Consumer 처리 대기 (최대 10초)

**테스트 항목**:
1. `order.created` E2E: Outbox 저장 → Kafka 발행 → Payment(PENDING) 생성 + Notification(ORDER_CREATED) 생성
2. `payment.completed` E2E: 주문 상태 PAYMENT_COMPLETED 전이 + Notification(PAYMENT_COMPLETED) 생성
3. `payment.failed` E2E: 주문 취소 + 재고 복구(100→98→100) + Notification(PAYMENT_FAILED) 생성
4. `order.cancelled` E2E: NotificationConsumer만 소비, Payment 미생성 확인
5. PUBLISHED 이벤트 중복 발행 방지: 재폴링 시 Notification 수 변화 없음

**주요 결정**:
- **KafkaContainer 이미지**: `org.testcontainers.kafka.KafkaContainer`(`apache/kafka:3.8.1`) 사용. `org.testcontainers.containers.KafkaContainer`는 `confluentinc/cp-kafka` 전용이므로 별도 패키지의 클래스 사용
- **스케줄러 제어**: `spring.task.scheduling.pool.size=1` 유지 + `pollAndPublish()` 수동 호출. pool.size=0은 Spring Boot에서 `IllegalArgumentException` 발생
- **재고 복구 테스트**: 테스트에서 재고를 미리 차감한 뒤 복구를 검증 (차감 없이 복구하면 100→102로 초과)

**Phase 2 Exit Criteria 달성**: Outbox → Kafka 이벤트 발행 정상 동작 ✅

전체 227건 테스트 통과 확인.

### 2026-04-01 (2)

#### Task 2-4: Consumer 멱등성 구현 (통합 테스트 제외)

**완료 항목**:
- `ProcessedEvent` Entity 생성 (`global/idempotency/`) — `(event_id, consumer_group)` 복합 UK, `create()` 팩토리
- `ProcessedEventRepository` 계층 생성 (인터페이스 + JpaRepository + Impl)
- `IdempotencyChecker` 컴포넌트 생성 — `executeIfNew(eventId, consumerGroup, Runnable)` 단일 메서드
- Consumer 3개(7메서드) 멱등성 적용:
  - `OrderEventConsumer` (2메서드): `order-svc-payment-completed-group`, `order-svc-payment-failed-group`
  - `PaymentEventConsumer` (1메서드): `payment-svc-order-created-group`
  - `NotificationConsumer` (4메서드): `notification-svc-order-created-group`, `notification-svc-payment-completed-group`, `notification-svc-payment-failed-group`, `notification-svc-order-cancelled-group`
- Consumer `extractPayload()` → `parseMessage()` 리네이밍 (root JsonNode 반환, eventId + payload 추출)
- 기존 전체 테스트 227건 통과 확인

**미완료**: 멱등성 통합 테스트 (동일 이벤트 중복 소비 검증, 다른 consumerGroup 독립 처리 검증)

**주요 결정**:
- **IdempotencyChecker 공유 컴포넌트 방식 채택**: 7개 메서드에 동일한 check-execute-record 패턴 반복을 피하기 위해 `Runnable`을 받는 간결한 헬퍼 컴포넌트로 구현. 상속/AOP/어노테이션 없이 단일 메서드로 해결
- **트랜잭션 참여 방식**: `IdempotencyChecker`는 자체 트랜잭션을 관리하지 않고 Consumer의 `@Transactional`에 참여. 비즈니스 로직 + `processed_events` 기록이 단일 트랜잭션으로 원자성 보장. 실패 시 전체 롤백 → 재시도 시 재처리 가능
- **parseMessage() 리팩토링**: 기존 `extractPayload()`가 `root.get("payload")`만 반환하여 `eventId` 접근 불가. `parseMessage()`로 변경하여 root JsonNode 반환, eventId + payload null 체크 포함

#### Task 2-4: 코드 리뷰 개선 (4건)

설계서 전체 대조 + 코드 리뷰 수행 후 아래 항목 개선 완료:

**P0 — IdempotencyChecker race condition 수정**:
- 기존: `exists()` → `action.run()` → `save()` 순서 — Kafka 리밸런스 시 동일 메시지 동시 소비 시 두 스레드 모두 `exists()=false` 통과 → 비즈니스 로직 이중 실행 가능
- 변경: `exists()` → `save()` → `action.run()` (save-first) — UK 제약이 선점 락 역할, 동시 진입 시 하나만 insert 성공, 나머지는 `DataIntegrityViolationException` catch → `return false`
- `action.run()` 실패 시 Consumer의 `@Transactional` 롤백으로 `processed_events` 레코드도 함께 삭제 → 재시도 시 재처리 가능

**P1 — parseMessage() 3중 중복 → KafkaMessageParser 추출**:
- 3개 Consumer에 완전히 동일한 `parseMessage()` 메서드(11줄)가 복사되어 있던 문제
- `global/kafka/KafkaMessageParser` 공통 컴포넌트 생성, Consumer 3개에서 `kafkaMessageParser.parse()` 호출로 변경
- Consumer에서 `ObjectMapper` 직접 의존 제거

**P1 — Consumer Group ID 문자열 이중 관리 → 상수 추출**:
- `@KafkaListener(groupId = "...")` 어노테이션과 `idempotencyChecker.executeIfNew(eventId, "...", ...)` 호출부에 동일 문자열 하드코딩 — 오타 시 멱등성 깨짐
- Consumer별 `private static final String GROUP_*` 상수 추출, 두 곳에서 동일 상수 참조

**P2 — 02-architecture.md 패키지 구조 동기화**:
- `global/idempotency/` 하위 파일 2개 → 5개로 업데이트 (`ProcessedEventJpaRepository`, `ProcessedEventRepositoryImpl`, `IdempotencyChecker` 추가)
- `global/kafka/KafkaMessageParser` 추가

전체 227건 테스트 통과 확인.

### 2026-04-02

#### Task 2-4: 멱등성 통합 테스트 완료 (2건) — Task 2-4 완료

`IdempotencyIntegrationTest` 작성 — Testcontainers Kafka + MySQL + Redis 기반 멱등성 검증:

**테스트 항목**:
1. **동일 이벤트 중복 소비 방지**: `order.created` Outbox → Kafka 발행 → Consumer 처리 완료 후, 동일 eventId 메시지를 KafkaTemplate으로 재전송 → Payment/Notification 수 변화 없음 (3초 동안 안정성 확인)
2. **다른 consumer group 독립 처리**: `order.created` 이벤트를 PaymentEventConsumer(`payment-svc-order-created-group`)와 NotificationConsumer(`notification-svc-order-created-group`)가 각각 독립 처리 → `processed_events`에 같은 eventId로 2건 존재, consumer group 상이

**검증 방식**:
- 중복 테스트: `await().during(3s)` — 재전송 후 충분한 시간 동안 부수효과 없음 확인
- 독립 처리 테스트: `ProcessedEventJpaRepository.findAll()` 직접 조회 → eventId 필터링 + consumerGroup 검증

전체 229건 테스트 통과 확인.

### 2026-04-02 (2)

#### Task 2-5: DLQ 구성 (통합 테스트 제외)

**완료 항목**:
- `FixedSequenceBackOff` 생성 (`global/kafka/`) — `BackOff` 인터페이스 구현, `long[]` 배열 기반 커스텀 재시도 간격 (1s, 5s, 30s)
- `KafkaConfig` 확장:
  - DLQ 토픽 4개 `NewTopic` Bean 추가 (`order.created.dlq`, `payment.completed.dlq`, `payment.failed.dlq`, `order.cancelled.dlq`, 파티션 1)
  - `DeadLetterPublishingRecoverer` — destination resolver로 `{topic}.dlq` 라우팅
  - recoverer 람다에서 `SlackPort.send()` 호출 (Slack 알림)
  - `DefaultErrorHandler` — recoverer + `FixedSequenceBackOff(1000, 5000, 30000)`
  - `ConcurrentKafkaListenerContainerFactory` Bean 등록 (ackMode=RECORD, commonErrorHandler 설정)
- `FixedSequenceBackOffTest` 단위 테스트 3건 (간격 순서, 빈 배열, 독립 실행)
- 기존 Consumer 3개(7메서드) 변경 없음

**미완료**: DLQ 통합 테스트 (처리 실패 → DLQ 토픽 라우팅 검증)

**주요 결정**:
- **FixedSequenceBackOff 커스텀 구현**: 1s/5s/30s는 정확한 exponential이 아님 (multiplier=5이면 1s, 5s, 25s). `BackOff` 인터페이스를 직접 구현하여 정확한 간격 배열 적용
- **Slack 알림 시점**: 별도 DLQ Consumer 없이 `DeadLetterPublishingRecoverer`를 감싸는 recoverer 람다에서 처리 (PHASE2.md P1-5 결정 사항 준수)
- **ConcurrentKafkaListenerContainerFactory 수동 등록**: `DefaultErrorHandler`를 설정하려면 커스텀 factory 필요. `ConsumerFactory`는 auto-config Bean을 주입받아 deserializer 등 설정 유지, `ackMode=RECORD`만 수동 설정
- **Consumer 코드 변경 불필요**: `DefaultErrorHandler`가 listener container 레벨에서 동작. `@Transactional` + `IdempotencyChecker` save-first 패턴과 호환 (롤백 시 재시도 가능)

전체 232건 테스트 통과 확인.

#### Task 2-5: 코드 리뷰 개선 (3건)

설계서 대조 + 코드 리뷰 수행 후 아래 항목 개선 완료:

**P0 — slackPort.send() 예외 안전 처리**:
- recoverer 람다에서 `slackPort.send()`가 예외를 던지면 `DefaultErrorHandler`가 해당 레코드를 재시도 → DLQ에 동일 메시지 중복 발행 가능
- `slackPort.send()`를 try-catch로 감싸 Slack 실패가 DLQ 발행에 영향을 주지 않도록 수정

**P1 — 설계 문서 "exponential backoff" 용어 수정**:
- `04-design-deep-dive.md` 8-3: "exponential backoff: 1s, 5s, 30s" → "fixed sequence backoff: 1s, 5s, 30s"
- 1→5→30은 지수 패턴이 아님 (지수라면 1→2→4). 구현체 `FixedSequenceBackOff` 명칭과 일치하도록 수정

**P2 — 02-architecture.md 패키지 구조 동기화**:
- `global/kafka/` 하위에 `FixedSequenceBackOff.java` 추가 (기존 `KafkaMessageParser.java`만 등록되어 있었음)

전체 232건 테스트 통과 확인.

---

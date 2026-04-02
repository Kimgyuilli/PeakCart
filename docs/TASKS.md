# PeekCart — Task 관리

> Phase별 작업 항목과 현재 상태를 추적합니다.
> 상태: `🔲 대기` / `🔄 진행 중` / `✅ 완료` / `⏸ 보류`

---

## 현재 Phase: Phase 1 — 모놀리식 구현

**Phase 1 Exit Criteria** (`docs/07-roadmap-portfolio.md` 참고):
- [x] 모든 도메인 CRUD API 정상 동작 (Swagger UI 기준)
- [x] 주문 → 결제 → 알림 전체 플로우 정상 처리
- [x] 주문 상태 전이 검증 완료 (결제 성공/실패/타임아웃)
- [x] 결제 타임아웃 스케줄러 동작 확인

---

## Phase 1 Tasks

### Task 1-1: 프로젝트 초기 설정
**상태**: ✅ 완료
**목표**: 빌드 구성, 공통 구조, 로컬 개발 환경 세팅 완료

| 항목 | 상태 | 비고 |
|------|------|------|
| `settings.gradle` 생성 | ✅ | |
| `build.gradle` 작성 (의존성 포함) | ✅ | |
| `docker-compose.yml` 작성 (MySQL + Redis) | ✅ | |
| `application.yml` / `application-local.yml` | ✅ | |
| `V1__init_schema.sql` Flyway 초기 스키마 | ✅ | Phase 1 ERD 기준 |
| `PeekCartApplication.java` | ✅ | |
| `global/response/ApiResponse.java` | ✅ | 표준 응답 포맷 |
| `global/exception/ErrorCode.java` | ✅ | 도메인별 에러 코드 |
| `global/exception/BusinessException.java` | ✅ | 추상 예외 클래스 |
| `global/exception/GlobalExceptionHandler.java` | ✅ | |
| `global/config/SecurityConfig.java` | ✅ | |
| `global/config/RedisConfig.java` | ✅ | JWT 블랙리스트 전용 |
| `global/jwt/JwtProvider.java` | ✅ | |
| `global/jwt/JwtFilter.java` | ✅ | |
**완료 기준**: 애플리케이션 구동 + Swagger UI 접근 + Docker Compose 정상 실행

---

### Task 1-2: User 도메인
**상태**: ✅ 완료
**목표**: 회원가입/로그인, JWT 인증, RBAC 구현

| 항목 | 상태 | 비고 |
|------|------|------|
| `User` Entity + 비즈니스 로직 | ✅ | BaseEntity 상속, create/matchesPassword/updateProfile |
| `RefreshToken` Entity | ✅ | BaseTimeEntity 상속, DB 저장 |
| `Address` Entity | ✅ | |
| `UserRole` Enum (VO) | ✅ | |
| `UserRepository` 인터페이스 | ✅ | |
| `UserRepositoryImpl` + `UserJpaRepository` | ✅ | |
| `RefreshTokenRepository` 인터페이스 | ✅ | |
| `RefreshTokenRepositoryImpl` + `RefreshTokenJpaRepository` | ✅ | |
| `TokenBlacklistRepository` (Redis) | ✅ | 블랙리스트(bl:) + 그레이스 피리어드(gp:) |
| `AuthService` — 회원가입/로그인/로그아웃/토큰 재발급 | ✅ | Grace Period 포함 |
| `UserCommandService` / `UserQueryService` | ✅ | |
| `AuthController` / `UserController` | ✅ | |
| 단위 테스트 (Domain + Application) | ✅ | Domain 4건 + Application 14건, 전부 통과 |
| 슬라이스 테스트 (Presentation) | ✅ | AuthController 5건 + UserController 3건, 전부 통과 |

**완료 기준**: 회원가입 → 로그인 → 토큰 재발급 → 로그아웃 시나리오 정상 동작

---

### Task 1-3: Product 도메인
**상태**: ✅ 완료
**목표**: 상품 CRUD (관리자), 목록/상세 조회, 재고 관리

| 항목 | 상태 | 비고 |
|------|------|------|
| `Product` Entity | ✅ | BaseTimeEntity 상속, update/discontinue/isOnSale |
| `Category` Entity | ✅ | 자기 참조 (parent_id), @ManyToOne LAZY |
| `Inventory` Entity | ✅ | @Version 낙관적 락, decrease/restore 비즈니스 메서드 |
| `ProductStatus` Enum (VO) | ✅ | ON_SALE / SOLD_OUT / DISCONTINUED |
| Repository 계층 | ✅ | 인터페이스 3개 + JPA 3개 + Impl 3개 |
| `ProductCommandService` (관리자 CRUD) | ✅ | create(Product+Inventory 단일 트랜잭션), update, delete(soft) |
| `ProductQueryService` (목록/상세, 페이징) | ✅ | ON_SALE 필터, categoryId 옵션 |
| `InventoryService` (재고 차감/복구) | ✅ | Order 도메인 호출 대상 |
| `ProductController` / `AdminProductController` | ✅ | RBAC 적용, SecurityConfig 공개 URL 추가 |
| 단위 테스트 | ✅ | Domain 11건 + Application 16건 + Presentation 10건, 전부 통과 |

**완료 기준**: 상품 등록 → 목록 조회 (페이징/카테고리 필터) → 상세 조회 정상 동작

---

### Task 1-4: Order 도메인
**상태**: ✅ 완료
**목표**: 장바구니, 주문 생성 (재고 즉시 차감), 주문 상태 전이, 이벤트 발행

| 항목 | 상태 | 비고 |
|------|------|------|
| `Order` Entity (상태 전이 로직 포함) | ✅ | cancel(), transitionTo(), OrderItemData로 순환 의존 제거 |
| `OrderItem` Entity | ✅ | `unit_price` 스냅샷, 패키지 내부 생성자 |
| `OrderStatus` Enum (VO) | ✅ | 8개 상태 + canTransitionTo() 전이 규칙 캡슐화 |
| `Cart` / `CartItem` Entity | ✅ | addItem 중복 병합, get-or-create 패턴 |
| Repository 계층 | ✅ | 인터페이스 2개 + JPA 2개 + Impl 2개 |
| `OrderCommandService` — 주문 생성, 취소 | ✅ | 재고 즉시 차감 + 이벤트 발행 |
| `OrderQueryService` — 주문 내역 (페이징) | ✅ | |
| `CartService` — 장바구니 CRUD | ✅ | CartCommandService + CartQueryService 분리 |
| `OrderEventListener` (`@TransactionalEventListener`) | ✅ | payment.approved/failed 수신 → 주문 상태 전이 + 재고 복구 (Task 1-5에서 구현) |
| `OrderController` / `CartController` | ✅ | |
| 단위 테스트 | ✅ | Domain 58건 + Application 16건 + Presentation 15건 = 89건, 전부 통과 |

**완료 기준**: 장바구니 → 주문 생성 (재고 차감) → 주문 취소 (재고 복구) 정상 동작

---

### Task 1-5: Payment 도메인
**상태**: ✅ 완료
**목표**: Toss Payments 연동, 결제 승인/실패, 웹훅 수신

| 항목 | 상태 | 비고 |
|------|------|------|
| `Payment` Entity | ✅ | PENDING/APPROVED/FAILED 상태 전이, validateAmount, assignPaymentKey |
| `PaymentStatus` Enum (VO) | ✅ | canTransitionTo() 전이 규칙 캡슐화 |
| Repository 계층 | ✅ | 인터페이스 2개 + JPA 2개 + Impl 2개 (Payment, WebhookLog) |
| `PaymentCommandService` — 결제 승인/실패 | ✅ | userId 소유권 검증 + Toss API 연동 + 이벤트 발행 |
| `PaymentQueryService` | ✅ | userId 소유권 검증 포함 |
| `TossPaymentClient` — Toss API 연동 | ✅ | RestClient, Basic Auth |
| `PaymentEventListener` (`@TransactionalEventListener`) | ✅ | order.created 수신 → Payment(PENDING) 생성 |
| `OrderEventListener` (`@TransactionalEventListener`) | ✅ | payment.approved/failed 수신 → 주문 상태 전이 + 재고 복구 |
| `PaymentController` — 결제 승인, 조회, 웹훅 | ✅ | HMAC 서명 검증은 WebhookService에서 처리 |
| `webhook_logs` 저장 (멱등성 처리) | ✅ | `idempotency_key` UK, WebhookService에서 관리 |
| `OrderPort` + `OrderPortAdapter` | ✅ | Payment → Order 크로스 도메인 DIP |
| 단위 테스트 | ✅ | Domain 22건 + Application 12건 + Presentation 7건 = 41건, 전부 통과 |

**완료 기준**: 결제 승인 → PAYMENT_COMPLETED 상태 전이 + 웹훅 수신 정상 처리

---

### Task 1-6: Notification 도메인
**상태**: ✅ 완료
**목표**: 이벤트 수신 → Slack Webhook 알림 발송, 알림 내역 저장

| 항목 | 상태 | 비고 |
|------|------|------|
| `Notification` Entity | ✅ | NotificationType VO 포함, create |
| Repository 계층 | ✅ | 인터페이스 + JPA + Impl |
| `NotificationCommandService` | ✅ | 알림 생성 + Slack 발송 (SlackPort DIP) |
| `NotificationQueryService` | ✅ | 페이징 조회 |
| `NotificationEventListener` (`@TransactionalEventListener`) | ✅ | order.created, payment.completed/failed, order.cancelled 수신 |
| `SlackNotificationClient` — Slack Webhook 발송 | ✅ | SlackPort 구현체, RestClient, 실패 시 로그만 |
| `NotificationController` — 알림 내역 조회 | ✅ | GET /api/v1/notifications |
| 단위 테스트 | ✅ | Domain 4건 + Application 4건 + Presentation 2건 = 10건, 전부 통과 |

**완료 기준**: 주문 생성 → Slack 알림 수신 확인

---

### Task 1-7: 결제 타임아웃 처리
**상태**: ✅ 완료
**목표**: 15분 초과 주문 자동 취소 + 재고 복구 스케줄러

| 항목 | 상태 | 비고 |
|------|------|------|
| `OrderTimeoutScheduler` (`@Scheduled`) | ✅ | 60초 주기, 단일 인스턴스, ShedLock 없음 |
| `PAYMENT_REQUESTED` 상태 15분 초과 조회 | ✅ | JOIN FETCH JPQL, 인덱스: `idx_orders_status_ordered_at` |
| 자동 취소 + 재고 복구 트랜잭션 | ✅ | REQUIRES_NEW 건별 독립 트랜잭션, 실패 격리 |
| 단위 테스트 | ✅ | Service 2건 + Scheduler 3건 = 5건, 전부 통과 |

**완료 기준**: 15분 초과 주문이 CANCELLED로 자동 전이 + 재고 복구 확인

---

## 현재 Phase: Phase 2 — 성능 개선

**Phase 2 Exit Criteria** (`docs/07-roadmap-portfolio.md` 참고):
- [x] Redis 캐싱 적용 후 통합 테스트에서 캐시 적중/무효화 동작 확인
- [x] 동시 주문 테스트 시 오버셀링 0건
- [x] Outbox → Kafka 이벤트 발행 정상 동작
- [ ] DLQ 토픽으로 실패 메시지 라우팅 확인

---

## Phase 2 Tasks

### Task 2-1: Redis 캐싱
**상태**: ✅ 완료
**목표**: 상품 목록/상세 조회에 Cache Aside 패턴 적용, 응답시간 개선

| 항목 | 상태 | 비고 |
|------|------|------|
| `build.gradle` Redis 캐싱 의존성 추가 (spring-boot-starter-cache) | ✅ | |
| `CacheConfig` 설정 (RedisCacheManager, TTL, 직렬화) | ✅ | JSON 직렬화, product 30분 / products 10분 TTL |
| `ProductQueryService` 상품 상세 조회 캐싱 (`@Cacheable`) | ✅ | ProductCacheService 분리 (AOP 프록시), ProductInfoDto(재고 제외) 캐싱 |
| `ProductQueryService` 상품 목록 조회 캐싱 | ✅ | CachedPage 래퍼, ProductListDto, 페이징+카테고리 조건별 캐시 키 |
| `ProductCommandService` 상품 수정/삭제 시 캐시 무효화 (`@CacheEvict`) | ✅ | create→목록 evict, update/delete→상세+목록 evict |
| 통합 테스트 (캐시 적중/무효화 검증) | ✅ | Testcontainers Redis + MySQL, 캐시 적중/무효화 5건 |

> **캐시와 재고 분리**: 캐시에 재고를 포함하지 않습니다. 재고는 차감/복구마다 변경되어 캐시 무효화가 빈번하고, PK 단건 조회(~1ms)로 충분합니다. `@CacheEvict`는 상품 수정/삭제 시에만 동작하며, 재고 변경과 캐시가 결합되지 않아 구현이 단순합니다. 대안(재고 포함 + TTL 30초)의 트레이드오프도 인지합니다.

**완료 기준**: 캐시 적중 시 DB 조회 없이 응답, 상품 변경 시 캐시 즉시 무효화

---

### Task 2-2: Redis 분산 락 (재고 동시성 제어)
**상태**: ✅ 완료
**목표**: Redisson 분산 락 + DB 낙관적 락 이중 방어로 오버셀링 방지

| 항목 | 상태 | 비고 |
|------|------|------|
| `build.gradle` Redisson 의존성 추가 | ✅ | `org.redisson:redisson:3.27.0` |
| `RedissonConfig` 설정 | ✅ | `RedisConnectionDetails` 주입, Testcontainers `@ServiceConnection` 호환 |
| `DistributedLockManager` 구현 (Redisson RLock) | ✅ | 키: `inventory-lock:{productId}`, waitTime 3s / leaseTime 5s, Redis 장애 시 fallback |
| `InventoryService` 분산 락 적용 (재고 차감) | ✅ | 락 획득 실패 → PRD-004(409), `ProductPortAdapter` → `InventoryService` 위임으로 통합 |
| 동시성 통합 테스트 (멀티스레드 오버셀링 검증) | ✅ | Testcontainers Redis, 50스레드 동시 차감, 오버셀링 0건 |

> **데드락 방지**: 다중 상품 주문 시 productId 오름차순 정렬 후 순차 락 획득 (global ordering).
> **Redis 장애 fallback**: `DistributedLockManager`에서 Redis 연결 예외 catch → 락 없이 진행, `@Version` 낙관적 락이 최후 방어선 (설계 9-1). 동시 요청 시 낙관적 락 충돌률 증가를 감수하는 트레이드오프.

**완료 기준**: 동시 주문 테스트 시 오버셀링 0건, Redis 장애 시 DB 낙관적 락 fallback 동작

---

### Task 2-3: Kafka + Outbox 도입
**상태**: ✅ 완료
**목표**: `@TransactionalEventListener` → Outbox 패턴 + Kafka 전환, 이벤트 유실 방지

| 항목 | 상태 | 비고 |
|------|------|------|
| `docker-compose.yml` Kafka (KRaft) 추가 | ✅ | apache/kafka:3.8.1, KRaft 모드 |
| `build.gradle` spring-kafka 의존성 추가 | ✅ | spring-kafka + spring-kafka-test + testcontainers:kafka |
| Flyway `V2__outbox_processed_events.sql` 스키마 추가 | ✅ | outbox_events + processed_events(복합 UK) + 인덱스 |
| `OutboxEvent` Entity (`global/outbox/`) | ✅ | PENDING/PUBLISHED/FAILED 상태, retry_count, 횡단 관심사 |
| `OutboxEventRepository` 계층 (`global/outbox/`) | ✅ | 단일 Repository — 도메인 구분은 aggregate_type 컬럼 |
| `OrderOutboxEventPublisher` / `PaymentOutboxEventPublisher` | ✅ | 도메인별 Publisher (infrastructure/outbox/), 기존 `ApplicationEventPublisher` 대체 |
| `OutboxPollingScheduler` (`global/outbox/`) | ✅ | 5초 주기, PENDING 조회 → Kafka 발행 → PUBLISHED, 실패 시 retry_count 증가 |
| Outbox FAILED 시 Slack 알림 발송 | ✅ | retry 초과(MAX_RETRY=5) → FAILED 상태 + SlackPort로 알림 |
| 이벤트 페이로드 DTO 정의 | ✅ | KafkaEventEnvelope 래핑 + 4개 Payload record (global/outbox/dto/) |
| `KafkaConfig` 설정 (Producer/Consumer/Topic) | ✅ | 파티션 키: orderId, Consumer Group 네이밍 규칙 적용 |
| 기존 `@TransactionalEventListener` → Kafka Consumer 전환 | ✅ | PaymentEventConsumer, OrderEventConsumer, NotificationConsumer |
| Kafka 토픽 생성 설정 | ✅ | 4개 토픽, 파티션 3, Replication 1 (KafkaConfig NewTopic Bean) |
| 통합 테스트 (Outbox → Kafka 발행 → Consumer 수신 검증) | ✅ | Testcontainers Kafka + MySQL + Redis, E2E 5건 |

> **Phase 2 이벤트 소비 경로**:
> - `order.created` → PaymentEventConsumer(결제 생성) + NotificationConsumer(알림)
> - `payment.completed` → OrderEventConsumer(주문 상태 전이) + NotificationConsumer(알림)
> - `payment.failed` → OrderEventConsumer(주문 취소 + 재고 복구 직접 호출) + NotificationConsumer(알림)
> - `order.cancelled` → NotificationConsumer만 소비 (Product Consumer 분리는 Phase 4)
>
> **Phase 2 재고 복구 경로**: 모놀리스이므로 `cancelOrder()` 내에서 `inventoryService.restoreStock()` 직접 호출을 유지합니다. Product 도메인의 Kafka Consumer 분리는 Phase 4(MSA)에서 수행합니다.

**완료 기준**: 주문 생성 → Outbox 저장 → Kafka 발행 → Consumer 수신 전체 플로우 정상 동작

---

### Task 2-4: Consumer 멱등성
**상태**: ✅ 완료
**목표**: `processed_events` 테이블 기반 중복 소비 방지

| 항목 | 상태 | 비고 |
|------|------|------|
| `ProcessedEvent` Entity (`global/idempotency/`) | ✅ | `(event_id, consumer_group)` 복합 UK |
| `ProcessedEventRepository` 계층 (`global/idempotency/`) | ✅ | 인터페이스 + JPA + Impl |
| Consumer 멱등성 처리 로직 (event_id + consumer_group 중복 체크) | ✅ | `IdempotencyChecker.executeIfNew()` + Consumer 3개(7메서드) 적용 |
| 멱등성 통합 테스트 (동일 이벤트 2회 소비 시 1회만 처리) | ✅ | Testcontainers Kafka + MySQL + Redis, 2건 |

**완료 기준**: 동일 (event_id, consumer_group) 중복 소비 시 1회만 실행, 다른 consumer_group은 독립 처리

---

### Task 2-5: DLQ 구성
**상태**: 🔲 대기
**목표**: Consumer 재시도 실패 시 DLQ 토픽 라우팅 + Slack 알림

| 항목 | 상태 | 비고 |
|------|------|------|
| Consumer 재시도 정책 설정 (3회, exponential backoff) | 🔲 | 1s, 5s, 30s |
| DLQ 토픽 설정 (`{원본토픽}.dlq`) | 🔲 | 4개 DLQ 토픽 |
| `DeadLetterPublishingRecoverer` 설정 | 🔲 | 재시도 초과 → DLQ 라우팅 |
| DLQ 메시지 수신 시 Slack 알림 발송 | 🔲 | 기존 SlackPort 재사용 |
| DLQ 통합 테스트 (처리 실패 → DLQ 토픽 라우팅 검증) | 🔲 | |

**완료 기준**: Consumer 3회 재시도 실패 → DLQ 토픽 이동 + Slack 알림 발송

---

### Task 2-6: ShedLock
**상태**: 🔲 대기
**목표**: 타임아웃/Outbox 스케줄러에 ShedLock 적용, 분산 환경 중복 실행 방지

| 항목 | 상태 | 비고 |
|------|------|------|
| `build.gradle` ShedLock 의존성 추가 | 🔲 | shedlock-spring + shedlock-provider-jdbc-template |
| Flyway `V3__shedlock.sql` 스키마 추가 | 🔲 | shedlock 테이블 |
| `ShedLockConfig` 설정 (`@EnableSchedulerLock`) | 🔲 | |
| `OrderTimeoutScheduler` ShedLock 적용 | 🔲 | `@SchedulerLock(name = "orderTimeoutCancelJob", lockAtMostFor = "PT10M")` |
| `OutboxPollingScheduler` ShedLock 적용 | 🔲 | `@SchedulerLock(name = "outboxPollingJob", lockAtMostFor = "PT5M")` |
| 통합 테스트 (ShedLock 동작 검증) | 🔲 | |

**완료 기준**: 스케줄러 중복 실행 방지 동작 확인

---

## 다음 Phase 예정

- **Phase 3**: GitHub Actions CI, minikube K8s, Prometheus + Grafana, 부하 테스트
- **Phase 4**: Gradle 멀티모듈, Spring Cloud Gateway, Choreography Saga, CQRS

---

## 완료된 작업

| 날짜 | 작업 | 내용 |
|------|------|------|
| 2026-03-21 | 문서 구조화 | README.md(진입점), docs/01~07 분리, 00-lagacy.md 보존 |
| 2026-03-21 | CLAUDE.md | 프로젝트 규칙 추가 |
| 2026-03-21 | TASKS.md | 태스크 관리 문서 초기화 |
| 2026-03-22 | Task 1-1 | 프로젝트 초기 설정 완료 (Gradle, Docker Compose, Flyway 스키마, global 공통 클래스) |
| 2026-03-22 | Task 1-2 | User 도메인 구현 완료 (회원가입/로그인/로그아웃/토큰 재발급, JWT 인증, RBAC, Grace Period) |
| 2026-03-25 | Task 1-3 | Product 도메인 완료 (엔티티, Repository, 서비스, Controller, 코드리뷰 개선, 단위 테스트 37건) |
| 2026-03-25 | Task 1-4 | Order 도메인 완료 (엔티티, Repository, 서비스, Controller, 단위 테스트 89건) |
| 2026-03-25 | Task 1-5 | Payment 도메인 완료 (엔티티, Repository, TossPaymentClient, EventListener, Controller, OrderPort/Adapter) |
| 2026-03-25 | Task 1-5 테스트 | Payment 도메인 단위 테스트 완료 (Domain 22건 + Application 12건 + Presentation 7건 = 41건) |
| 2026-03-26 | Task 1-6 | Notification 도메인 완료 (엔티티, Repository, SlackPort DIP, EventListener, Controller, 코드리뷰 개선, 단위 테스트 10건) |
| 2026-03-26 | Task 1-7 | 결제 타임아웃 스케줄러 완료 (OrderTimeoutScheduler, cancelExpiredOrder, REQUIRES_NEW 건별 트랜잭션, 단위 테스트 5건) |
| 2026-03-26 | 커버리지 측정 | JaCoCo 설정 + 커버리지 측정 (Domain 100%, Application 99%, 전체 213건 통과) |
| 2026-03-27 | Swagger 문서화 | OpenApiConfig(JWT SecurityScheme), 8개 Controller @Tag/@Operation, @ParameterObject Pageable |
| 2026-03-27 | Swagger 개선 | 에러 핸들링 4건 보강, 204 No Content 통일, LoginUser 전역 숨김, Pageable 기본값 설정 |
| 2026-03-27 | 낙관적 락 동시성 테스트 | Inventory @Version 낙관적 락 통합 테스트 (Testcontainers, 10스레드 동시 차감, lost update 방지 검증), ErrorCode PRD_004 + GlobalExceptionHandler OptimisticLockingFailureException 409 처리 |
| 2026-03-29 | Task 2-1 | Redis 캐싱 완료 (Cache Aside 패턴, CacheConfig, ProductCacheService, CachedPage, 코드리뷰 개선 4건, 통합 테스트 5건) |
| 2026-03-30 | Task 2-2 | Redis 분산 락 완료 (Redisson, DistributedLockManager, InventoryLockFacade, 50스레드 동시성 통합 테스트, 오버셀링 0건) |
| 2026-03-31 | Task 2-3 (12/13) | Kafka + Outbox 구현 (KRaft, Flyway V2, OutboxEvent Entity/Repository, Publisher 2개, Scheduler, Consumer 3개, EventListener 비활성화, 기존 테스트 44건 통과). 통합 테스트 미완료 |
| 2026-03-31 | Task 2-3 코드 리뷰 | 설계 문서 대조 + 코드 리뷰 3건 개선: SlackPort를 global/port/로 이동(P0 아키텍처 위반), OutboxEvent 팩토리 Function 패턴 적용(P1), EventListener 미사용 import 제거(P1). 전체 222건 테스트 통과 |
| 2026-04-01 | Task 2-3 완료 | Outbox → Kafka E2E 통합 테스트 5건 (Testcontainers Kafka + MySQL + Redis, Awaitility 비동기 대기). 전체 227건 테스트 통과 |
| 2026-04-01 | Task 2-4 코드 리뷰 | 설계 문서 대조 + 코드 리뷰 4건 개선: IdempotencyChecker save-first + UK 선점 패턴(P0 race condition), KafkaMessageParser 공통 추출(P1 3중 중복), Consumer Group ID 상수 추출(P1 이중 관리), 02-architecture.md 패키지 구조 동기화(P2). 전체 227건 테스트 통과 |
| 2026-04-02 | Task 2-4 완료 | 멱등성 통합 테스트 2건 (Testcontainers Kafka + MySQL + Redis): 동일 이벤트 중복 소비 시 1회만 처리, 다른 consumer group 독립 처리. 전체 229건 테스트 통과 |

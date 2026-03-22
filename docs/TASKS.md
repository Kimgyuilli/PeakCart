# PeekCart — Task 관리

> Phase별 작업 항목과 현재 상태를 추적합니다.
> 상태: `🔲 대기` / `🔄 진행 중` / `✅ 완료` / `⏸ 보류`

---

## 현재 Phase: Phase 1 — 모놀리식 구현

**Phase 1 Exit Criteria** (`docs/07-roadmap-portfolio.md` 참고):
- [ ] 모든 도메인 CRUD API 정상 동작 (Swagger UI 기준)
- [ ] 주문 → 결제 → 알림 전체 플로우 정상 처리
- [ ] 주문 상태 전이 검증 완료 (결제 성공/실패/타임아웃)
- [ ] 결제 타임아웃 스케줄러 동작 확인

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
**상태**: 🔲 대기
**목표**: 회원가입/로그인, JWT 인증, RBAC 구현

| 항목 | 상태 | 비고 |
|------|------|------|
| `User` Entity + 비즈니스 로직 | 🔲 | |
| `RefreshToken` Entity | 🔲 | DB 저장 |
| `Address` Entity | 🔲 | |
| `UserRole` Enum (VO) | 🔲 | |
| `UserRepository` 인터페이스 | 🔲 | |
| `UserRepositoryImpl` + `UserJpaRepository` | 🔲 | |
| `TokenBlacklistRepository` (Redis) | 🔲 | 블랙리스트 전용 |
| `AuthService` — 회원가입/로그인/로그아웃/토큰 재발급 | 🔲 | Grace Period 포함 |
| `UserCommandService` / `UserQueryService` | 🔲 | |
| `AuthController` / `UserController` | 🔲 | |
| 단위 테스트 (Domain + Application) | 🔲 | 커버리지 90%+ |
| 슬라이스 테스트 (Presentation) | 🔲 | |

**완료 기준**: 회원가입 → 로그인 → 토큰 재발급 → 로그아웃 시나리오 정상 동작

---

### Task 1-3: Product 도메인
**상태**: 🔲 대기
**목표**: 상품 CRUD (관리자), 목록/상세 조회, 재고 관리

| 항목 | 상태 | 비고 |
|------|------|------|
| `Product` Entity | 🔲 | |
| `Category` Entity | 🔲 | 자기 참조 (parent_id) |
| `Inventory` Entity | 🔲 | `version` 컬럼 (낙관적 락, Phase 1 fallback) |
| `ProductStatus` Enum (VO) | 🔲 | |
| Repository 계층 | 🔲 | |
| `ProductCommandService` (관리자 CRUD) | 🔲 | |
| `ProductQueryService` (목록/상세, 페이징) | 🔲 | |
| `InventoryService` (재고 차감/복구) | 🔲 | |
| `ProductController` / `AdminProductController` | 🔲 | RBAC 적용 |
| 단위 테스트 | 🔲 | |

**완료 기준**: 상품 등록 → 목록 조회 (페이징/카테고리 필터) → 상세 조회 정상 동작

---

### Task 1-4: Order 도메인
**상태**: 🔲 대기
**목표**: 장바구니, 주문 생성 (재고 즉시 차감), 주문 상태 전이, 이벤트 발행

| 항목 | 상태 | 비고 |
|------|------|------|
| `Order` Entity (상태 전이 로직 포함) | 🔲 | PENDING → DELIVERED/CANCELLED |
| `OrderItem` Entity | 🔲 | `unit_price` 스냅샷 |
| `OrderStatus` Enum (VO) | 🔲 | 8개 상태 |
| `Cart` / `CartItem` Entity | 🔲 | |
| Repository 계층 | 🔲 | |
| `OrderCommandService` — 주문 생성, 취소 | 🔲 | 재고 즉시 차감 + 이벤트 발행 |
| `OrderQueryService` — 주문 내역 (페이징) | 🔲 | |
| `CartService` — 장바구니 CRUD | 🔲 | |
| `OrderEventListener` (`@TransactionalEventListener`) | 🔲 | payment.failed 수신 시 보상 |
| `OrderController` / `CartController` | 🔲 | |
| 단위 테스트 (상태 전이 + 멀티스레드 동시성) | 🔲 | |

**완료 기준**: 장바구니 → 주문 생성 (재고 차감) → 주문 취소 (재고 복구) 정상 동작

---

### Task 1-5: Payment 도메인
**상태**: 🔲 대기
**목표**: Toss Payments 연동, 결제 승인/실패, 웹훅 수신

| 항목 | 상태 | 비고 |
|------|------|------|
| `Payment` Entity | 🔲 | |
| `PaymentStatus` Enum (VO) | 🔲 | |
| Repository 계층 | 🔲 | |
| `PaymentCommandService` — 결제 승인/실패 | 🔲 | |
| `PaymentQueryService` | 🔲 | |
| `TossPaymentClient` — Toss API 연동 | 🔲 | WebClient 또는 RestClient |
| `PaymentEventListener` (`@TransactionalEventListener`) | 🔲 | order.created 수신 |
| `PaymentController` — 결제 승인, 조회, 웹훅 | 🔲 | 웹훅 HMAC 서명 검증 |
| `webhook_logs` 저장 (멱등성 처리) | 🔲 | `idempotency_key` UK |
| 단위 테스트 | 🔲 | |

**완료 기준**: 결제 승인 → PAYMENT_COMPLETED 상태 전이 + 웹훅 수신 정상 처리

---

### Task 1-6: Notification 도메인
**상태**: 🔲 대기
**목표**: 이벤트 수신 → Slack Webhook 알림 발송, 알림 내역 저장

| 항목 | 상태 | 비고 |
|------|------|------|
| `Notification` Entity | 🔲 | |
| Repository 계층 | 🔲 | |
| `NotificationCommandService` | 🔲 | |
| `NotificationQueryService` | 🔲 | |
| `NotificationEventListener` (`@TransactionalEventListener`) | 🔲 | order.created, payment.* 수신 |
| `SlackNotificationClient` — Slack Webhook 발송 | 🔲 | |
| `NotificationController` — 알림 내역 조회 | 🔲 | |
| 단위 테스트 | 🔲 | |

**완료 기준**: 주문 생성 → Slack 알림 수신 확인

---

### Task 1-7: 결제 타임아웃 처리
**상태**: 🔲 대기
**목표**: 15분 초과 주문 자동 취소 + 재고 복구 스케줄러

| 항목 | 상태 | 비고 |
|------|------|------|
| `OrderTimeoutScheduler` (`@Scheduled`) | 🔲 | 단일 인스턴스, ShedLock 없음 |
| `PAYMENT_REQUESTED` 상태 15분 초과 조회 | 🔲 | 인덱스: `idx_orders_status_ordered_at` |
| 자동 취소 + 재고 복구 트랜잭션 | 🔲 | |
| 단위/통합 테스트 | 🔲 | |

**완료 기준**: 15분 초과 주문이 CANCELLED로 자동 전이 + 재고 복구 확인

---

## 다음 Phase 예정

- **Phase 2**: Redis 캐싱, Redisson 분산 락, Kafka + Outbox, DLQ, ShedLock
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

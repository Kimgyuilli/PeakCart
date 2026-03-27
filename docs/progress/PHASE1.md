# Phase 1 진행 보고서 — 모놀리식 구현

> Phase 1 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md` 참고

---

## Phase 1 목표

**Exit Criteria**:
- [ ] 모든 도메인 CRUD API 정상 동작 (Swagger UI 기준)
- [ ] 주문 → 결제 → 알림 전체 플로우 정상 처리
- [ ] 주문 상태 전이 검증 완료 (결제 성공/실패/타임아웃 시나리오)
- [ ] 결제 타임아웃 스케줄러 동작 확인

---

## 작업 이력

### 2026-03-21

#### 프로젝트 초기화 및 문서 구조화

**완료 항목**:
- README.md 진입점으로 재구성 (docs 링크 포함)
- `docs/01~07` 역할별 문서 분리 (원본 내용 100% 보존, 00-lagacy.md로 원본 보관)
- `CLAUDE.md` 프로젝트 규칙 섹션 추가
- `docs/TASKS.md` 태스크 관리 문서 초기화
- `settings.gradle` 생성

---

### 2026-03-22

#### Task 1-1: 프로젝트 초기 설정 완료

**완료 항목**:
- `build.gradle`: Spring Boot 3.5.x 기반 전체 의존성 구성 (Web, Security, JPA, Redis, MySQL, Flyway, JWT, Swagger, Lombok)
- `docker-compose.yml`: MySQL 8.0 + Redis 7.2 로컬 환경
- `application.yml` / `application-local.yml`: 프로파일 분리, JPA/Flyway/JWT 설정
- `db/migration/V1__init_schema.sql`: Phase 1 전체 스키마 (13개 테이블 + 인덱스)
- `global/response/ApiResponse`: 표준 성공 응답 포맷
- `global/exception/ErrorCode`: 도메인별 에러 코드 체계 (USR/PRD/ORD/PAY/SYS)
- `global/exception/BusinessException`: 추상 예외 기반 클래스
- `global/exception/GlobalExceptionHandler`: 전역 예외 핸들러 (에러 응답 포맷 통일)
- `global/config/SecurityConfig`: JWT 필터 연동, Stateless 세션, 공개 경로 허용
- `global/config/RedisConfig`: 블랙리스트용 `RedisTemplate<String, String>` 설정
- `global/jwt/JwtProvider`: Access Token 생성/검증 (jjwt 0.12.6)
- `global/jwt/JwtFilter`: 요청별 Bearer 토큰 파싱 → SecurityContext 주입

#### Task 1-2: User 도메인 구현 완료

**완료 항목**:
- `global/entity/BaseTimeEntity` / `BaseEntity`: created_at / updated_at 공통 필드 계층 분리
- `global/auth/TokenIssuer`: 토큰 발급 추상화 인터페이스 (isValid, parseToken 포함)
- `global/auth/LoginUser` / `@CurrentUser` / `LoginUserArgumentResolver`: Controller 인증 정보 추출 분리
- `global/config/WebMvcConfig`: ArgumentResolver 등록
- `user/domain/model/`: User, RefreshToken, Address, UserRole (model/repository/exception 패키지 분리)
- `user/domain/repository/`: UserRepository, RefreshTokenRepository 인터페이스
- `user/infrastructure/`: UserJpaRepository, UserRepositoryImpl, RefreshTokenJpaRepository, RefreshTokenRepositoryImpl
- `user/infrastructure/redis/TokenBlacklistRepository`: 블랙리스트(bl:) + 그레이스 피리어드(gp:) Redis 저장소
- `user/application/AuthService`: 회원가입/로그인/로그아웃/토큰 재발급 (Refresh Token Rotation + Grace Period 10초)
- `user/application/UserCommandService` / `UserQueryService`
- `user/presentation/AuthController` / `UserController` + DTO (request/response 패키지 분리)
- `global/config/SecurityConfig`: logout 엔드포인트 인증 필수화
- `global/jwt/JwtFilter`: 블랙리스트 검증 + `setDetails(token)` 추가
- 전체 소스 파일 JavaDoc 주석 추가 (37개 파일)

### 2026-03-25

#### Task 1-4: Order 도메인 구현 (프로덕션 코드)

**완료 항목**:
- `order/domain/model/OrderStatus`: 8개 상태 + `canTransitionTo()` 전이 규칙 캡슐화
- `order/domain/model/OrderItemData`: 순환 의존 제거용 값 객체 (Order.create() 인자)
- `order/domain/model/OrderItem`: 패키지 내부 생성자, `unit_price` 스냅샷, `getSubtotal()`
- `order/domain/model/Order`: `ordered_at` 직접 선언(BaseEntity 미상속), `cancel()` / `transitionTo()` 상태 전이 메서드
- `order/domain/model/CartItem`: 패키지 내부 `addQuantity()`, `changeQuantity()`
- `order/domain/model/Cart`: BaseTimeEntity 상속, `addItem()` 중복 병합, `clear()`
- `order/domain/event/OrderCreatedEvent` / `OrderCancelledEvent`: 도메인 이벤트 record
- `order/domain/exception/OrderException`: BusinessException 상속
- `order/domain/repository/`: OrderRepository, CartRepository 인터페이스
- `order/infrastructure/`: JPA Repository 2개 + RepositoryImpl 2개
- `global/exception/ErrorCode`: ORD_004~006 추가 (장바구니 비어있음 / 수량 오류 / 장바구니 미존재)
- `order/application/OrderCommandService`: 주문 생성(재고 즉시 차감 + 장바구니 비우기 + 이벤트 발행), 취소(재고 복구 + 이벤트 발행)
- `order/application/OrderQueryService`: 주문 목록(페이징) / 상세 조회
- `order/application/CartCommandService`: addItem(get-or-create) / updateItem / removeItem
- `order/application/CartQueryService`: 장바구니 조회 (없으면 빈 DTO 반환)
- `order/application/dto/`: CreateOrderCommand, AddCartItemCommand, UpdateCartItemCommand, OrderDetailDto, OrderItemDto, CartDetailDto, CartItemDto
- `order/presentation/OrderController`: POST/GET /api/v1/orders, GET /api/v1/orders/{id}, POST /api/v1/orders/{id}/cancel
- `order/presentation/CartController`: GET/POST/PUT/DELETE /api/v1/cart/items
- `order/presentation/dto/request/`: CreateOrderRequest, AddCartItemRequest, UpdateCartItemRequest
- `order/presentation/dto/response/`: OrderResponse, OrderDetailResponse, OrderItemResponse, CartResponse, CartItemResponse

**미완료 항목**:
- `OrderEventListener`: payment.failed 수신 시 보상 (Task 1-5에서 구현)

#### Task 1-4: Order 도메인 단위 테스트

**완료 항목**:
- `support/fixture/OrderFixture`: Order/Cart/CartItem 도메인 객체 + Application DTO 팩토리
- `order/domain/model/OrderStatusTest` (27건): 8개 상태 전이 규칙 전수 검증 (ParameterizedTest 포함)
- `order/domain/model/OrderTest` (11건): 생성 검증(상태/totalAmount/orderedAt), 빈 아이템 예외, cancel/transitionTo 상태 전이
- `order/domain/model/OrderItemTest` (4건): subtotal 계산, 수량 0 예외, 음수 단가 예외, 단가 0 정상 생성
- `order/domain/model/CartTest` (9건): 생성, addItem(추가/병합/별도항목), updateItemQuantity, removeItem, clear, 미존재 항목 예외
- `order/domain/model/CartItemTest` (7건): 생성, changeQuantity, addQuantity(합산/0/음수 delta 방어)
- `order/application/OrderCommandServiceTest` (5건): createOrder(성공/장바구니미존재/빈장바구니), cancelOrder(성공/미존재)
- `order/application/OrderQueryServiceTest` (3건): getOrders(페이징), getOrder(성공/미존재)
- `order/application/CartCommandServiceTest` (6건): addItem(기존/새장바구니), updateItem(성공/미존재), removeItem(성공/미존재)
- `order/application/CartQueryServiceTest` (2건): getCart(존재/미존재 시 빈 DTO)
- `order/presentation/OrderControllerTest` (7건): POST 생성(201/400), GET 목록, GET 상세(200/404), POST 취소(200/400)
- `order/presentation/CartControllerTest` (8건): GET 조회(정상/빈DTO), POST 추가(201/400/400), PUT 수정(200), DELETE 삭제(204/404)
- 전체 89건 통과 (Domain 58건 + Application 16건 + Presentation 15건)

---

#### Task 1-3: Product 도메인 구현 (프로덕션 코드)

**완료 항목**:
- `product/domain/model/ProductStatus`: ON_SALE / SOLD_OUT / DISCONTINUED Enum
- `product/domain/model/Category`: 자기 참조(parent_id) 엔티티, 타임스탬프 없음
- `product/domain/model/Product`: BaseTimeEntity 상속, update/discontinue/isOnSale 비즈니스 메서드
- `product/domain/model/Inventory`: @Version 낙관적 락, @UpdateTimestamp, decrease/restore 비즈니스 메서드
- `product/domain/exception/ProductException`: BusinessException 상속
- `product/domain/repository/`: ProductRepository, CategoryRepository, InventoryRepository 인터페이스
- `product/infrastructure/`: JPA Repository 3개 + RepositoryImpl 3개
- `global/exception/ErrorCode`: PRD_003(카테고리 미존재) 추가
- `product/application/ProductCommandService`: create(Product+Inventory 단일 트랜잭션), update, delete(soft)
- `product/application/ProductQueryService`: ON_SALE 목록 페이징, categoryId 필터, 상세 조회
- `product/application/InventoryService`: decreaseStock/restoreStock (Order 도메인 호출 대상)
- `product/application/dto/ProductDetailDto`: Application 레이어 DTO
- `product/presentation/dto/request/`: CreateProductRequest, UpdateProductRequest
- `product/presentation/dto/response/`: ProductResponse(목록용), ProductDetailResponse(상세용)
- `product/presentation/ProductController`: GET /api/v1/products, GET /api/v1/products/{id} (공개)
- `product/presentation/AdminProductController`: POST/PUT/DELETE /api/v1/admin/products (hasRole ADMIN)
- `global/config/SecurityConfig`: /api/v1/products/** 공개 URL 추가

**미완료 항목**:
- 단위 테스트: 코드 리뷰 후 작성 예정

---

### 2026-03-25 (Task 1-5)

#### Task 1-5: Payment 도메인 구현

**완료 항목**:
- `payment/domain/model/PaymentStatus`: PENDING/APPROVED/FAILED 3상태 + `canTransitionTo()` 전이 규칙 캡슐화
- `payment/domain/model/Payment`: BaseEntity 미상속(`created_at`만 존재), create/assignPaymentKey/approve/fail/validateAmount
- `payment/domain/model/WebhookLog`: idempotency_key 기반 중복 방지 엔티티
- `payment/domain/exception/PaymentException`: BusinessException 상속
- `payment/domain/repository/`: PaymentRepository, WebhookLogRepository 인터페이스
- `payment/domain/event/`: PaymentApprovedEvent, PaymentFailedEvent 도메인 이벤트 레코드
- `payment/infrastructure/`: JPA Repository 2개 + RepositoryImpl 2개
- `payment/infrastructure/toss/TossPaymentClient`: RestClient 기반 Toss API 연동, Basic Auth
- `payment/infrastructure/toss/TossConfirmResponse`: Toss 응답 DTO
- `payment/infrastructure/event/PaymentEventListener`: OrderCreatedEvent 수신 → Payment(PENDING) 생성
- `order/infrastructure/event/OrderEventListener`: PaymentApprovedEvent → PAYMENT_COMPLETED 전이, PaymentFailedEvent → cancel() + 재고 복구 (Task 1-4 보류 항목)
- `payment/application/port/OrderPort`: verifyOrderOwner/transitionToPaymentRequested 인터페이스
- `order/infrastructure/adapter/OrderPortAdapter`: OrderPort 구현체 (ProductPortAdapter 패턴)
- `payment/application/PaymentCommandService`: userId 소유권 검증 + Toss 승인 + 이벤트 발행
- `payment/application/PaymentQueryService`: userId 소유권 검증 + 결제 조회
- `payment/application/WebhookService`: HMAC-SHA256 서명 검증 + idempotency_key 멱등성 처리
- `payment/application/dto/`: ConfirmPaymentCommand, PaymentDetailDto
- `payment/presentation/PaymentController`: POST /confirm, GET /{orderId}, POST /webhook
- `payment/presentation/dto/`: ConfirmPaymentRequest, PaymentResponse
- `global/exception/ErrorCode`: PAY_003~006 추가
- `global/config/SecurityConfig`: /api/v1/payments/webhook PUBLIC_URLS 추가
- `application.yml`: toss.payments 설정 추가

#### Task 1-5: Payment 도메인 단위 테스트 추가

**완료 항목**:
- `support/fixture/PaymentFixture`: 테스트 데이터 팩토리 (pendingPayment, approvedPayment, failedPayment, DTO 생성)
- `payment/domain/model/PaymentTest`: create/assignPaymentKey/approve/fail/validateAmount 상태 전이 및 검증 (16건)
- `payment/domain/model/PaymentStatusTest`: PENDING/APPROVED/FAILED 전이 규칙 검증 (5건)
- `payment/domain/model/WebhookLogTest`: 생성 검증 (1건)
- `payment/application/PaymentCommandServiceTest`: 승인 성공/Toss 실패/미존재/금액 불일치/소유권 검증 (5건)
- `payment/application/PaymentQueryServiceTest`: 조회 성공/미존재/소유권 검증 (3건)
- `payment/application/WebhookServiceTest`: HMAC 서명 검증/멱등성 스킵/null·불일치 서명 (4건)
- `payment/presentation/PaymentControllerTest`: confirm 성공/실패/validation, 조회 성공/미존재, webhook 성공/서명 실패 (7건)
- 총 41건 전부 통과

---

### 2026-03-26 (Task 1-6)

#### Task 1-6: Notification 도메인 구현 (테스트 제외)

**완료 항목**:
- `payment/domain/event/PaymentCompletedEvent`, `PaymentFailedEvent`: userId 필드 추가 (Notification에서 사용)
- `payment/application/PaymentCommandService`: 이벤트 발행 시 userId 전달
- `notification/domain/model/Notification`: Entity, create/markAsRead, BaseEntity 미상속 (created_at만)
- `notification/domain/model/NotificationType`: ORDER_CREATED, PAYMENT_COMPLETED, PAYMENT_FAILED, ORDER_CANCELLED
- `notification/domain/repository/NotificationRepository`: save, findByUserId 인터페이스
- `notification/domain/exception/NotificationException`: BusinessException 상속
- `notification/infrastructure/`: NotificationJpaRepository, NotificationRepositoryImpl
- `notification/infrastructure/slack/SlackNotificationClient`: RestClient 기반 Slack Webhook 발송, 실패 시 로그만
- `notification/infrastructure/event/NotificationEventListener`: 4개 이벤트 수신 (AFTER_COMMIT + REQUIRES_NEW)
- `notification/application/NotificationCommandService`: 알림 생성 + Slack 발송
- `notification/application/NotificationQueryService`: 페이징 조회
- `notification/application/dto/NotificationDetailDto`: Application DTO
- `notification/presentation/NotificationController`: GET /api/v1/notifications
- `notification/presentation/dto/response/NotificationResponse`: Presentation DTO
- `global/exception/ErrorCode`: NTF_001 추가
- `application.yml`: slack.webhook.url 설정 추가

**미완료 항목**: 없음

#### Task 1-6: 코드 리뷰 개선

**완료 항목**:
- `notification/application/port/SlackPort` 인터페이스 도입: Application → Infrastructure 의존 역전
- `SlackNotificationClient`: SlackPort 구현체로 변경
- `NotificationCommandService`: SlackNotificationClient 직접 의존 → SlackPort 포트 의존으로 교체
- `Notification.markAsRead()`: 미사용 메서드 제거 (호출처 없음, 명세에도 없음)
- `NotificationException` + `NTF_001` 에러 코드: 미사용 dead code 제거
- `docs/02-architecture.md`: notification 패키지 구조 보완 (presentation, port 추가)
- `docs/04-design-deep-dive.md`: payment.failed Consumer에 Notification Service 추가

#### Task 1-6: Notification 도메인 단위 테스트

**완료 항목**:
- `support/fixture/NotificationFixture`: 테스트 데이터 팩토리 (notification, notificationWithId, DTO 생성)
- `notification/domain/model/NotificationTest`: create 필드 검증, isRead 초기값, createdAt 설정, NotificationType 4종 (4건)
- `notification/application/NotificationCommandServiceTest`: 알림 저장 검증, Slack 발송 검증 (2건)
- `notification/application/NotificationQueryServiceTest`: 페이징 조회, 빈 페이지 반환 (2건)
- `notification/presentation/NotificationControllerTest`: 목록 조회 성공, 빈 목록 반환 (2건)
- 총 10건 전부 통과

---

### 2026-03-26 (Task 1-7)

#### Task 1-7: 결제 타임아웃 스케줄러 구현

**완료 항목**:
- `PeekcartApplication`: `@EnableScheduling` 추가
- `order/domain/repository/OrderRepository`: `findByStatusAndOrderedAtBefore` 메서드 추가
- `order/infrastructure/OrderJpaRepository`: JOIN FETCH JPQL 쿼리 (N+1 방지)
- `order/infrastructure/OrderRepositoryImpl`: 위임 메서드 추가
- `order/application/OrderCommandService`: `cancelExpiredOrder(orderId)` — REQUIRES_NEW 건별 독립 트랜잭션, 재고 복구 + OrderCancelledEvent 발행
- `order/infrastructure/scheduler/OrderTimeoutScheduler`: 60초 fixedDelay, 15분 초과 주문 조회 → 건별 취소, try-catch 실패 격리

**테스트**:
- `OrderCommandServiceTest`: cancelExpiredOrder 성공/미존재 (2건 추가, 기존 5건 포함 총 7건)
- `OrderTimeoutSchedulerTest`: 만료 주문 처리/빈 목록/실패 격리 (3건)
- `OrderFixture`: paymentRequestedOrderWithId 팩토리 추가
- 전체 테스트 통과

---

### 2026-03-23

#### Task 1-2: 테스트 코드 완성 및 아키텍처 정제

**완료 항목**:
- `global/auth/TokenClaims` 레코드: jjwt `Claims` 타입을 Application 레이어까지 노출하지 않도록 캡슐화
- `user/domain/repository/TokenBlacklistPort` 인터페이스: Application 레이어가 Redis 구현체에 직접 의존하지 않도록 역전
- `TokenBlacklistRepository` → `TokenBlacklistPort` 구현 추가
- `AuthService` 반환 타입 `TokenResponse` → `TokenResult` (application DTO 분리)
- `UserQueryService` / `UserCommandService` 반환 타입 `UserResponse` → `User` (도메인 객체 반환)
- `JwtFilter` `@Component` 제거 → `SecurityConfig`에서 직접 생성 (`@WebMvcTest` 슬라이스 격리)
- `global/config/TestSecurityConfig`: `@WebMvcTest`용 permitAll 보안 설정
- `support/WithMockLoginUser` / `WithMockLoginUserSecurityContextFactory`: 커스텀 SecurityContext 어노테이션
- `support/fixture/UserFixture`: 도메인 객체 생성 픽스처
- `user/domain/model/UserTest` (4건), `application/AuthServiceTest` (9건), `UserCommandServiceTest` (2건), `UserQueryServiceTest` (2건)
- `presentation/AuthControllerTest` (5건), `UserControllerTest` (3건)
- Infrastructure 통합 테스트 제거 (Testcontainer 의존 — CI 환경 구성 시 재도입)
- 전체 28건 통과

---

## 주요 결정 사항

| 날짜 | 항목 | 결정 | 근거 |
|------|------|------|------|
| 2026-03-21 | 문서 구조 | `docs/01~07` 분리, README를 진입점으로 | 역할별 접근 용이성, 원본 보존 |
| 2026-03-22 | JWT 블랙리스트 | DB 저장 + Redis 블랙리스트 분리 구조 | 영속성(DB) + 즉시 무효화(Redis) 역할 분리 |
| 2026-03-22 | `application-local.yml` | `.gitignore` 처리 (미커밋) | 개발자별 로컬 설정 분리, 시크릿 노출 방지 |
| 2026-03-22 | BaseEntity 계층 | `BaseTimeEntity`(created_at) → `BaseEntity`(+updated_at) 분리 | RefreshToken은 updated_at 컬럼 없음, ddl-auto: validate 충돌 방지 |
| 2026-03-22 | logout 인증 필수화 | PUBLIC_URLS에서 logout 제거, JwtFilter setDetails(token) 추가 | 로그아웃 요청 자체가 유효한 토큰을 블랙리스트에 등록하는 행위이므로 인증 필요 |
| 2026-03-22 | ArgumentResolver 도입 | `@CurrentUser LoginUser`로 Controller 인증 정보 추출 분리 | Controller가 SecurityContext를 직접 다루지 않도록 관심사 분리 |
| 2026-03-22 | TokenIssuer 인터페이스 | Application 레이어가 JwtProvider 구현에 직접 의존하지 않도록 역전 | refreshTokenExpiry, UUID 생성 등 인프라 세부사항을 Application 레이어에서 제거 |
| 2026-03-23 | TokenBlacklistPort 인터페이스 | Application 레이어가 `TokenBlacklistRepository`(Redis 구현체)에 직접 의존하지 않도록 역전 | 의존 방향 준수: Application → Domain ← Infrastructure |
| 2026-03-23 | TokenClaims 레코드 | jjwt `Claims` 타입을 `global/auth`로 캡슐화, Application 레이어에서 jjwt 직접 참조 제거 | 인프라 라이브러리 타입이 Application 경계 밖으로 노출되는 것 방지 |
| 2026-03-23 | Application DTO 분리 | `AuthService` → `TokenResult`, `UserService` → `User` 반환 / Presentation에서 Response DTO로 변환 | Application 레이어가 Presentation DTO에 의존하지 않도록 경계 정리 |
| 2026-03-23 | JwtFilter `@Component` 제거 | `SecurityConfig`에서 직접 생성 | `@WebMvcTest` 슬라이스 테스트 시 JwtFilter가 컴포넌트 스캔에 포함되어 JwtProvider 빈 미등록으로 실패하는 문제 해결 |
| 2026-03-23 | Infrastructure 통합 테스트 제거 | Testcontainer 의존 통합 테스트 전면 제거 | 문제가 자주 발생하는 부분에만 집중, CI 환경 구성 시 재도입 예정 |
| 2026-03-25 | Inventory 베이스 클래스 미상속 | `Inventory`는 `BaseEntity` 상속 없이 `@UpdateTimestamp` 직접 선언 | `inventories` 테이블에 `created_at` 없음, `ddl-auto: validate` 충돌 방지 (RefreshToken과 동일 사유) |
| 2026-03-25 | 상품 삭제 soft delete | DELETE API → `status = DISCONTINUED` 전환 | `order_items.product_id` FK 참조 무결성 보존 |
| 2026-03-25 | Product+Inventory 단일 트랜잭션 | `ProductCommandService.create()`에서 상품과 초기 재고를 하나의 트랜잭션으로 생성 | 상품 등록 후 재고 없는 상태 방지 |
| 2026-03-25 | Order BaseEntity 미상속 | `orders` 테이블 컬럼이 `ordered_at`뿐이므로 BaseEntity 미상속, 필드 직접 선언 | `ddl-auto: validate` 충돌 방지 (Inventory, RefreshToken과 동일 사유) |
| 2026-03-25 | OrderItemData 값 객체 도입 | `Order.create()`가 `OrderItem`을 직접 생성하여 순환 의존 제거 | Order → OrderItem, OrderItem → Order 상호 참조로 인한 팩토리 메서드 구현 불가 해결 |
| 2026-03-25 | CartService Command/Query 분리 | `CartCommandService` + `CartQueryService`로 분리 (계획상 단일 CartService) | 기존 Product/Order 패턴 일관성 유지 |
| 2026-03-25 | OrderEventListener 구현 보류 | payment.failed 소비자 구현을 Task 1-5로 이연 | 소비자 없이 이벤트 발행만으로 Task 1-5 연동 준비 완료 |
| 2026-03-25 | Payment BaseEntity 미상속 | `payments` 테이블에 `created_at`만 존재하므로 BaseEntity 미상속, `created_at` 직접 선언 | `ddl-auto: validate` 충돌 방지 (Inventory, RefreshToken과 동일 사유) |
| 2026-03-25 | Payment 생성 시 UUID paymentKey | OrderCreatedEvent 수신 시 Toss paymentKey 미확정이므로 UUID 임시값 부여, confirm 시 실제 키로 교체 | payments.payment_key NOT NULL 제약 + UNIQUE 보장 필요 |
| 2026-03-25 | HMAC 검증을 WebhookService로 | Controller에서 HMAC 검증 로직 제거, WebhookService가 서명 검증 + 멱등성 처리를 일괄 담당 | Controller는 파싱만, 비즈니스 로직은 Service 책임 |
| 2026-03-25 | OrderPort 도입 (Payment → Order DIP) | Payment application 레이어가 Order 도메인 내부에 직접 의존하지 않도록 OrderPort 인터페이스 도입 | ProductPort 패턴과 동일, 의존 방향 준수 |
| 2026-03-25 | TossPaymentClient에 RestClient 사용 |  spring-boot-starter-web에 내장된 RestClient(Spring 6.1+) 선택, 추가 의존성 없음 | WebClient(webflux 의존)나 RestTemplate(deprecated 경로) 대신 선택 |
| 2026-03-26 | PaymentEvent에 userId 추가 | PaymentCompletedEvent/PaymentFailedEvent 레코드에 userId 필드 추가 | Notification 생성 시 userId 필요, Port 조회 대신 이벤트에 포함하여 단순화 |
| 2026-03-26 | SlackNotificationClient 실패 무시 | Slack 발송 실패 시 try-catch로 로그만 남기고 예외 전파 안 함 | 알림 실패가 알림 DB 저장이나 원본 트랜잭션에 영향 주지 않도록 |
| 2026-03-26 | Notification BaseEntity 미상속 | `notifications` 테이블에 `created_at`만 존재하므로 BaseEntity 미상속 | `ddl-auto: validate` 충돌 방지 (Payment, Inventory와 동일 사유) |
| 2026-03-26 | SlackPort 인터페이스 도입 | NotificationCommandService가 SlackNotificationClient(infrastructure)에 직접 의존하던 구조를 SlackPort 포트로 역전 | TokenBlacklistPort, OrderPort 패턴과 동일, 의존 방향 준수 |
| 2026-03-26 | markAsRead/NotificationException 제거 | 호출처 없는 markAsRead(), 미사용 NotificationException + NTF_001 에러 코드 제거 | 명세에 없는 스펙 제거, dead code 정리 |
| 2026-03-26 | cancelExpiredOrder 별도 메서드 | 기존 cancelOrder(userId, orderId) 재사용 안 함, 스케줄러 전용 cancelExpiredOrder(orderId) 신설 | cancelOrder는 userId 소유권 검증 포함 — 스케줄러는 시스템 작업이므로 불필요 |
| 2026-03-26 | REQUIRES_NEW 건별 트랜잭션 | cancelExpiredOrder에 Propagation.REQUIRES_NEW 적용, 스케줄러에서 try-catch 감싸기 | 한 건 실패가 나머지 주문 취소에 영향 주지 않도록 실패 격리 |
| 2026-03-27 | LoginUser 전역 숨김 | Controller별 `@Parameter(hidden=true)` 대신 `OpenApiConfig`에서 `SpringDocUtils.addRequestWrapperToIgnore` | 한 곳에서 관리, Controller 코드에 Swagger 관심사 미침투 |
| 2026-03-27 | logout/cancelOrder 204 통일 | `ApiResponse.ok()` → `ResponseEntity.noContent().build()` | body 없는 작업은 204가 REST 관례, DELETE(noContent) 패턴과 일관 |
| 2026-03-27 | 낙관적 락 동시성 테스트 assertion | `successCount == 1` 대신 `successCount + conflictCount == threadCount` + `conflictCount >= 1` + 최종 재고 검증 | DB 타이밍에 따라 성공 수가 비결정적이므로, lost update 없음을 최종 재고로 검증하는 게 안정적 |
| 2026-03-27 | OptimisticLockingFailureException → 409 | `GlobalExceptionHandler`에서 `OptimisticLockingFailureException` → ErrorCode PRD_004 (409 Conflict) | 클라이언트에 재시도 가능한 충돌임을 명확히 전달 |

---

## 이슈 / 트레이드오프 기록

> 구현 과정에서 발생한 의사결정, 트레이드오프, 주의사항을 기록합니다.
> `docs/04-design-deep-dive.md`의 설계 결정 사항도 함께 참고하세요.

### 2026-03-27 (낙관적 락 동시성 테스트)

#### Inventory @Version 낙관적 락 통합 테스트

**완료 항목**:
- `product/infrastructure/InventoryConcurrencyTest`: @SpringBootTest + Testcontainers 통합 테스트
  - 10스레드 동시 재고 차감 → 낙관적 락 충돌 발생 검증
  - 최종 재고 = 초기 재고 - (성공 수 × 차감량) — lost update 없음 검증
- `ErrorCode.PRD_004`: 재고 변경 충돌 에러 코드 (409 Conflict)
- `GlobalExceptionHandler`: `OptimisticLockingFailureException` → 409 응답 핸들러 추가

**리뷰 후 수정 사항**:
- `@DataJpaTest` → `@SpringBootTest` 변경 (멀티스레드 EntityManager 직접 관리에 적합)
- `successCount == 1` → 비결정적 assertion 제거, `successCount + conflictCount == threadCount` + 최종 재고 검증으로 안정화

---

### 2026-03-27 (Swagger 문서화 + API 개선)

#### Swagger 문서화
**완료 항목**:
- `OpenApiConfig`: 프로젝트 메타정보 + JWT Bearer SecurityScheme + `LoginUser` 전역 숨김 (`SpringDocUtils.addRequestWrapperToIgnore`)
- 8개 Controller에 `@Tag` + `@Operation` 추가 (20개 엔드포인트)
- `@ParameterObject` + `@PageableDefault(size=20)` 적용 (Product, Order, Notification)
- `application.yml`: `spring.data.web.pageable` 기본값/최대값 설정

#### API 개선
**완료 항목**:
- `GlobalExceptionHandler`: `HttpMessageNotReadableException`(400), `HttpRequestMethodNotSupportedException`(405), `MissingServletRequestParameterException`(400), `InvalidDataAccessApiUsageException`(400) 추가
- `logout()`, `cancelOrder()`: 200 + `ApiResponse.ok()` → 204 No Content로 통일 (DELETE 패턴 일관성)
- 슬라이스 테스트 2건 수정 (200 → 204)
- Swagger UI에서 전체 API 동작 확인 완료

---

### 2026-03-26 (커버리지 측정)

#### JaCoCo 커버리지 설정 및 측정

**완료 항목**:
- `build.gradle`: JaCoCo 플러그인 추가, `jacocoTestReport` 태스크 설정 (DTO/config/Application 클래스 제외)
- 전체 213건 테스트 통과 확인
- 커버리지 측정 결과: Domain 216/216 (100%), Application 203/205 (99%)

---

## Phase 1 완료 시 체크리스트

### 기능 동작
- [x] `POST /api/v1/auth/signup` — 회원가입
- [x] `POST /api/v1/auth/login` — 로그인 (JWT 발급)
- [x] `POST /api/v1/auth/refresh` — 토큰 재발급 (Grace Period)
- [x] `POST /api/v1/auth/logout` — 로그아웃 (Redis 블랙리스트)
- [x] `GET/PUT /api/v1/users/me` — 내 정보 조회/수정
- [x] `GET /api/v1/products` — 상품 목록 (페이징, 카테고리 필터)
- [x] `GET /api/v1/products/{id}` — 상품 상세
- [x] 관리자 상품 CRUD (`/api/v1/admin/products`)
- [x] 장바구니 CRUD (`/api/v1/cart`)
- [x] `POST /api/v1/orders` — 주문 생성 (재고 즉시 차감)
- [x] `POST /api/v1/orders/{id}/cancel` — 주문 취소
- [x] `POST /api/v1/payments/confirm` — 결제 승인
- [x] `POST /api/v1/payments/webhook` — 웹훅 수신 (HMAC 검증)
- [x] `GET /api/v1/notifications` — 알림 내역

### 플로우 검증
- [x] 주문 생성 → `@TransactionalEventListener` → 결제 요청 이벤트 전달
- [x] 결제 성공 → 주문 상태 PAYMENT_COMPLETED 전이
- [x] 결제 실패 → 주문 취소 + 재고 복구 (보상 트랜잭션)
- [x] 주문 생성 → Slack 알림 수신
- [x] 15분 타임아웃 → 주문 자동 취소 + 재고 복구

### 기술 검증
- [x] Docker Compose (`MySQL + Redis`) 정상 구동
- [x] Flyway V1 마이그레이션 정상 적용
- [x] Swagger UI 모든 API 명세 확인 + 동작 테스트 완료
- [x] 단위 테스트: Domain 100%, Application 99% (JaCoCo 측정 완료)
- [x] 낙관적 락 (`inventories.version`) 동시성 보호 확인

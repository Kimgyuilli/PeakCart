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

---

## 이슈 / 트레이드오프 기록

> 구현 과정에서 발생한 의사결정, 트레이드오프, 주의사항을 기록합니다.
> `docs/04-design-deep-dive.md`의 설계 결정 사항도 함께 참고하세요.

---

## Phase 1 완료 시 체크리스트

### 기능 동작
- [x] `POST /api/v1/auth/signup` — 회원가입
- [x] `POST /api/v1/auth/login` — 로그인 (JWT 발급)
- [x] `POST /api/v1/auth/refresh` — 토큰 재발급 (Grace Period)
- [x] `POST /api/v1/auth/logout` — 로그아웃 (Redis 블랙리스트)
- [x] `GET/PUT /api/v1/users/me` — 내 정보 조회/수정
- [ ] `GET /api/v1/products` — 상품 목록 (페이징, 카테고리 필터)
- [ ] `GET /api/v1/products/{id}` — 상품 상세
- [ ] 관리자 상품 CRUD (`/api/v1/admin/products`)
- [ ] 장바구니 CRUD (`/api/v1/cart`)
- [ ] `POST /api/v1/orders` — 주문 생성 (재고 즉시 차감)
- [ ] `POST /api/v1/orders/{id}/cancel` — 주문 취소
- [ ] `POST /api/v1/payments/confirm` — 결제 승인
- [ ] `POST /api/v1/payments/webhook` — 웹훅 수신 (HMAC 검증)
- [ ] `GET /api/v1/notifications` — 알림 내역

### 플로우 검증
- [ ] 주문 생성 → `@TransactionalEventListener` → 결제 요청 이벤트 전달
- [ ] 결제 성공 → 주문 상태 PAYMENT_COMPLETED 전이
- [ ] 결제 실패 → 주문 취소 + 재고 복구 (보상 트랜잭션)
- [ ] 주문 생성 → Slack 알림 수신
- [ ] 15분 타임아웃 → 주문 자동 취소 + 재고 복구

### 기술 검증
- [x] Docker Compose (`MySQL + Redis`) 정상 구동
- [x] Flyway V1 마이그레이션 정상 적용
- [ ] Swagger UI 모든 API 명세 확인
- [ ] 단위 테스트: Domain 90%+, Application 80%+
- [ ] 낙관적 락 (`inventories.version`) 동시성 보호 확인

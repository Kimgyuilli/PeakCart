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

**진행 중**:
- Task 1-1: 프로젝트 초기 설정 (build.gradle, docker-compose.yml, application.yml 등)

---

## 주요 결정 사항

| 날짜 | 항목 | 결정 | 근거 |
|------|------|------|------|
| 2026-03-21 | 문서 구조 | `docs/01~07` 분리, README를 진입점으로 | 역할별 접근 용이성, 원본 보존 |

---

## 이슈 / 트레이드오프 기록

> 구현 과정에서 발생한 의사결정, 트레이드오프, 주의사항을 기록합니다.
> `docs/04-design-deep-dive.md`의 설계 결정 사항도 함께 참고하세요.

---

## Phase 1 완료 시 체크리스트

### 기능 동작
- [ ] `POST /api/v1/auth/signup` — 회원가입
- [ ] `POST /api/v1/auth/login` — 로그인 (JWT 발급)
- [ ] `POST /api/v1/auth/refresh` — 토큰 재발급 (Grace Period)
- [ ] `POST /api/v1/auth/logout` — 로그아웃 (Redis 블랙리스트)
- [ ] `GET/PUT /api/v1/users/me` — 내 정보 조회/수정
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
- [ ] Docker Compose (`MySQL + Redis`) 정상 구동
- [ ] Flyway V1 마이그레이션 정상 적용
- [ ] Swagger UI 모든 API 명세 확인
- [ ] 단위 테스트: Domain 90%+, Application 80%+
- [ ] 낙관적 락 (`inventories.version`) 동시성 보호 확인

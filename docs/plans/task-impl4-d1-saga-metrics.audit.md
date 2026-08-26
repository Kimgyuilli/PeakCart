# ④-d 계획 리뷰 audit (d-1 / d-2 공통 이력)

## 2026-08-26 16:26 — 라운드 1
- 항목: **12건 (P0:4, P1:7, P2:1)** · 반영 12 / 기각 0
- **뒤집힌 전제 (P0 4건, 전부 코드로 직접 확인)**:
  1. §4 E2E SQL 이 **실제 스키마와 불일치** — `inventories.quantity`(실제 `stock`, `V1__init_product.sql:35`) · `stock_reservations.refund_status`(실제 `refund_result`/`refund_resolved_at`/`refund_failure_code`, `V4:11-13`). **첫 줄에서 실패할 쿼리였다**
  2. **매트릭스 게이트가 논리적으로 불가능** — 매트릭스가 기대 행의 유일한 입력이면 행 삭제는 검사 대상만 줄여 통과한다. "행을 지우면 실패" 주장이 자기모순 → lint 내부 required-ID 정본 도입
  3. **`TossPaymentClient` 가 baseUrl 하드코딩**(`:36-44`) — PG 대역이 없어 환불 성공 체인 E2E 도달 불가 → 트리거 구간까지로 축소
  4. **CI 이미지가 PR 에 전달되지 않는다** — `images` 는 6개 별도 matrix 러너이고 artifact 업로드가 `if: github.event_name == 'push'`(`ci.yml:137-145`). `needs:` 는 순서만 보장 → PR 에서도 업로드 + e2e 잡에서 `docker load`
- 그 외 P1: §2.1 "메트릭 0건" 자기모순(환불 5종 인정하면서 제목이 0건) · order 보상 계측 지점 오지정(엔티티가 아니라 `OrderEventConsumer:227-238`) · **Counter 로는 alert 불가**(잔량 Gauge 필요) · §4 쿼리 미완성·기대 scalar 부재·`processed_events` 는 `(event_id, consumer_group)` 유니크 · CI lint 가 gradle build **보다 먼저** 실행돼 JUnit XML 부재 · 부모 P14 필수 행 미승계 · **alert 발화 검증은 수행 수단 없음**(ADR-0015:72-74 가 범위 밖 규정)
- 그 외 P2: "cross-service E2E 인프라 0건" 과장 — compose·smoke 하네스·Testcontainers 는 이미 있다. 정확한 공백은 "여러 서비스를 동시 기동해 하나의 saga 를 검증하는 하네스"
- raw: `.cache/codex-reviews/plan-d-r1.json`

## 2026-08-26 16:35 — 라운드 2
- 항목: **9건 (P0:2, P1:5, P2:2)** · 반영 9 / 기각 0
- **통과 확인**: 1R 에서 고친 스키마명·테이블·컬럼·consumer group 문자열이 마이그레이션 재대조에서 **전부 실제와 일치**
- **1라운드 수정이 만든 새 결함**:
  1. **(P0)** 트리거 구간 축소가 만든 구멍 — `RefundDispatcher` 는 `@Scheduled` 이고 **비활성화 프로퍼티가 없다**. fence 가 생기면 dispatcher 가 집어 **CI 러너가 `api.tosspayments.com` 실제 운영 API 로 나간다**. `REQUESTED 또는 CLAIMED` 단언도 플래키(4xx→`FAILED`, 타임아웃→`UNRESOLVED`, Order 원장은 `REFUND_FAILED` 로 경합)
  2. **(P0)** 증적 프로토콜과 CI 실행 순서 모순 — P9 가 모든 행의 실행 증적을 검사하는데 build 잡에는 E2E manifest 가 아직 없다
  3. JUnit XML 의 `testcase@name` 은 **메서드명이 아니라 `@DisplayName` 문자열**이다 — 제 프로토콜로는 실행된 테스트도 못 찾는다
  4. `observability-promql-lint` 의 라벨 invariant 는 **기존 UID 4종에만** 적용 — 신규 alert 는 라벨을 빼거나 삭제해도 통과한다
  5. **부모 P12 의 예약 실패 체인 누락** — 그러면서 ④ 완료를 선언했다
  6. `docker-compose.yml` 이 `container_name`·호스트 포트 고정 → project 이름만 바꿔도 격리 안 됨
  7. 시나리오 ② #4 에 `<producer>` placeholder 잔존 — "완전한 쿼리 정본" 선언과 어긋남
- **영역 분포**: E2E 하네스·시나리오 5건 · 매트릭스 게이트 3건 · **메트릭/alert 1건**
- raw: `.cache/codex-reviews/plan-d-r2.json`

## 2026-08-26 16:40 — 분할 결정
- 9건 중 **8건이 E2E + 게이트 절반**에 있고 메트릭(P11)은 1건뿐이다. P11 은 E2E 인프라에 의존하지 않는다.
- → **④-d-1**(P11 관측성, 즉시 착수) / **④-d-2**(P12 E2E · P14 게이트 · P15 종결) 로 분할.
- ④-d-2 가 흡수한 선결 과제: `RefundDispatcher` 비활성화 프로퍼티 · E2E 전용 compose(격리) · 예약 실패 체인 시나리오 · `observability-promql-lint` required-UID 확장(신규 alert 분) · 증적 프로토콜 3분기.
- **④ 종결은 ④-d-2 소관이다** — d-1 은 ④ 를 닫지 않는다.

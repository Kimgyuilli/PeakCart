# ④-d-2 — cross-service E2E · 계약 게이트 · ④ 종결 (범위 정의)

> 부모 계획: `docs/plans/task-impl4-choreography-saga.md` **P12 · P14 · P15**
> 형제: ④-d-1 (P11 관측성) — `task-impl4-d1-saga-metrics.md`
> 상태: **🔲 착수 전** — 계획 리뷰 2라운드 지적 8건을 선결 과제로 안고 있다. 착수 시 `/plan` 재실행
> 리뷰 이력: `task-impl4-d1-saga-metrics.audit.md` (d-1/d-2 공통)
> **이 PR 이 ④ 를 종결한다.**

---

## 1. 왜 분리됐나

계획 리뷰 2라운드 9건의 영역 분포: **E2E 하네스·시나리오 5건 · 매트릭스 게이트 3건 · 메트릭/alert 1건.**
메트릭(P11)은 E2E 인프라에 의존하지 않고 지적도 1건뿐이라 ④-d-1 로 먼저 냈다.

---

## 2. 착수 전 해결해야 할 것 (2R 지적 승계)

### D1. `RefundDispatcher` 를 E2E 에서 차단해야 한다 (2R #1, P0)

`RefundDispatcher` 는 `@Scheduled(fixedDelayString="${app.refund.dispatch-interval-ms}")` 이고 **비활성화 프로퍼티가 없다**(`RefundDispatcher.java:38-70`). `TossPaymentClient` 는 baseUrl 을 `https://api.tosspayments.com/v1` 로 하드코딩한다(`:36-44`).

→ E2E 에서 fence 행이 생기면 **dispatcher 가 집어서 CI 러너가 Toss 운영 API 로 나간다.** 플래키 이전에 그 자체가 문제다.
→ 상태 단언도 불안정하다: 4xx → `FAILED`, 연결 실패·타임아웃 재시도 소진 → `UNRESOLVED`(`RefundExecutor:35-54` · `PaymentRefundService:161-165`). Order 원장도 `OPEN` → `REFUND_FAILED` 로 경합한다.

**필요한 것**: `app.refund.dispatch-enabled`(기본 `true`) 같은 명시적 프로퍼티 + `@ConditionalOnProperty`. **production 기본값이 켜짐임을 보장하는 바인딩 테스트**를 함께 둔다 — 기본값을 잘못 두면 운영에서 환불이 조용히 멈춘다.

### D2. 증적 검사를 단계별로 분리해야 한다 (2R #2, P0)

매트릭스 게이트가 "모든 행의 실행 증적" 을 검사하는데, JVM 테스트 증적은 `build` 잡에서, 셀 E2E manifest 는 그 뒤 `e2e` 잡에서 생긴다. 한 번에 검사하면 build 단계에서 정상 실행도 실패하고, 그 행을 건너뛰면 "미실행 차단" 계약이 깨진다.

**필요한 것**: `--structure` / `--jvm-evidence` / `--e2e-evidence` 3분기. build 잡은 구조+JUnit, e2e 잡은 구조+manifest. **행별 evidence type 이 잘못된 단계에서 조용히 제외되지 않도록** type 별 required-ID 집합과 음성 self-test.

### D3. JUnit 증적 키를 실제 XML 계약에 맞춰야 한다 (2R #3)

Gradle 산출 XML 의 `<testcase name>` 은 **Java 메서드명이 아니라 `@DisplayName` 문자열**이다 (예: `name="payment.refunded(FAILED) → 실패 코드와 함께 기록"`). 별도 method 속성이 없다.

**필요한 것**: 증적 키를 `testsuite/testcase@classname + testcase@name` 으로 정의하고 display name 변경 위험을 감수하거나, **테스트 display name 에 안정적인 contract ID 를 포함**시켜 그 ID 를 찾는다. 중복 `classname+name` 도 실패시킨다.

### D4. E2E compose 격리 (2R #6)

`docker-compose.yml` 이 `container_name: peekcart-mysql|kafka|redis` 와 호스트 포트 3306/6379/9092 를 **고정**한다(`:4-9,15-18,24-37`). project 이름만 바꿔도 컨테이너명·포트가 충돌한다.

**필요한 것**: E2E 전용 compose(또는 override)에서 `container_name` 제거 + 호스트 포트 미노출(내부 네트워크). **서로 다른 project 두 개를 병렬 기동하는 self-test.**

### D5. 예약 실패 체인 시나리오 (2R #5)

부모 §5 P12 는 **"결제 실패 체인·예약 실패 체인"** 을 요구한다. 초안은 결제 실패 + 환불 트리거만 두고 예약 실패를 빠뜨린 채 ④ 완료를 선언했다.

**필요한 것**: 재고 부족을 주입해 `stock.reservation.result(success=false)` → Order 취소 → 원장 종결까지 확인하는 독립 시나리오.

### D6. 신규 alert 의 라벨 계약 (2R #4 — ④-d-1 이 선행 처리)

`observability-promql-lint` 의 라벨 invariant 는 기존 UID 4종에만 적용된다. ④-d-1 이 신규 alert UID 를 required 집합에 넣고 self-test 를 추가한다 → **d-2 는 그 확장 위에 매트릭스 게이트만 얹는다.**

### D7. E2E readiness 정본 집합 (2R #9)

"필수 토픽·consumer group" 을 문서에 열거하지 않으면 구현자가 일부만 검사하고 만족했다고 주장할 수 있다.

**필요한 것**: 시나리오별 required topic/group 을 **정확 집합으로 고정**하고 실제 `@KafkaListener` 상수와 대조. group describe 는 존재가 아니라 **active member ≥ 1 이고 할당 partition ≥ 1** 을 본다.

### D8. 증적 파서 자체의 false-green 검증 (2R #8)

self-test 가 매트릭스 구조만 훼손하면 파서의 결함은 안 잡힌다.

**필요한 것**: fixture XML/manifest 로 **missing · failure · error · skipped · duplicate · stale** 각각 non-zero 확인. E2E manifest 와 실패 시 수집한 상태를 `if: always()` artifact 로 업로드하고 manifest 에 run ID·commit SHA·시나리오 ID 포함.

---

## 3. 1R 에서 이미 확정된 것 (승계)

| 항목 | 결정 |
|---|---|
| E2E 형태 | **스크립트**(`scripts/saga-e2e-smoke.sh`). `e2e-tests` Gradle 모듈은 ADR-0011 D1 모듈 목록 개정 사안이라 배제 |
| 환불 체인 범위 | **트리거 구간까지**(트리거 2경로 → fence 1행). dispatcher→PG→회신→종결은 PG 대역 부재로 미도달 → **부모 P12 를 완전히 닫지 못한다** |
| CI 이미지 전달 | `images` 가 **PR 에서도** artifact 업로드 → `e2e` 잡이 다운로드 + `docker load`. 현재는 `if: github.event_name == 'push'` 라 PR 에 이미지가 없다 |
| 매트릭스 게이트 | lint 내부 **required-ID 정본**과 매트릭스 ID 집합 정확 일치. 매트릭스가 유일 입력이면 행 삭제를 못 잡는다 |
| CI 실행 순서 | policy lint 는 `./gradlew build` **보다 먼저** 돈다 → 매트릭스 게이트는 build 뒤에 배치 |
| test artifact glob | `build/reports/` 하나뿐 → `*/build/test-results/**` 로 확대 필요 |
| 매트릭스 필수 행 | refund result 3종 × 소비자 3곳 · crash matrix 4칸 · `payment.failed` 수렴 · 예약 실패 · timeout 3종 · sweeper · DLQ intake |
| E2E SQL | 스키마 재대조 **통과** — `peekcart_*` 스키마명·`inventories.stock`·`refund_result`·consumer group 문자열 전부 실제와 일치 |

---

## 4. 착수 조건

1. **④-d-1 머지** (신규 alert 의 lint required 집합 확장이 선행)
2. **④-c-2a([#90]) 머지** (DLQ intake 매트릭스 행)
3. §2 의 D1~D8 을 계획서에 반영한 뒤 `/plan task-impl4-d2-saga-e2e-gate` 재실행

---

## 5. ④ 종결 시 명시할 미충족

이 PR 이 ④ 를 닫되 아래를 조건으로 적는다.

1. **부모 P12 를 완전히 닫지 못했다** — 환불 체인의 dispatcher→PG→회신→종결 절반은 PG 대역 부재로 E2E 미도달. 서비스별 통합테스트(④-c-1a/1b)가 덮고 매트릭스에 등재하지만 **cross-service 로 증명한 것은 아니다**
2. **alert 발화 미검증** — ADR-0015:72-74 가 정적 lint 범위를 규정
3. **DLQ replay 미구현** — ④-c-2b (ADR 선행)
4. **매트릭스 lint 는 "테스트가 옳은가" 를 못 본다** — 구조적 한계
5. **PG stub + Toss base URL 설정화** — D-020 과 묶는다. 그게 서면 환불 체인 E2E 전구간과 D-020 reconciliation 을 함께 닫을 수 있다

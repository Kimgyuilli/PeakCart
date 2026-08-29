# ④-d-2 계획 리뷰 audit

## 2026-08-27 13:59 — 계획 리뷰 라운드 1
- 항목: 13건 (P0:1, P1:9, P2:3)
- 처리: 반영 13건 / 기각 0건
- 뒤집힌 전제:
  - V8 — `testsuite@name` 은 classname 이 아니라 클래스 `@DisplayName`. FQCN 은 `testcase@classname`
  - V9 — 업무 consumer 클래스 8 → **9**. group 상수가 다음 줄에 있는 케이스로 grep 대조 불가
  - V6 — `Save image for publish` 도 push 전용이라 upload 조건만 제거하면 PR 에 파일 자체가 없음
- 결정: P0(④ 종결 자기모순) → PG stub 도입으로 환불 체인 전구간을 범위에 넣고 ④ 종결 유지 (사용자 승인)
- 신규 항목: P15(PG stub) · P16(음성 대조군 상시) · P17(스케줄러 배선) · P18(Flyway cold start)
- raw: `.cache/codex-reviews/plan-task-impl4-d2-saga-e2e-gate-1787806325.json`

## 2026-08-27 — 계획 리뷰 라운드 2
- 항목: 11건 (P0:0, P1:10, P2:1)
- 처리: 반영 10건 / 기각 1건
- 기각: R2 #7(`base-url` 필수값화) — "환경 불변 단일 값" 근거. **R3 에서 철회됨**
- 뒤집힌 전제:
  - V16 — outbox 행은 이미 직렬화된 문자열. SQL INSERT seed 는 publisher 직렬화를 **여전히 우회**
  - V17 — `DlqTopology` 의 group 은 업무 실패 소유자 group 이지 DLQ intake listener group 이 아님
  - V18 — `ALREADY_CANCELED`·reconciliation 이 `GET /payments/{key}` 를 항상 먼저 호출
  - V20 — `lockAtLeastFor=PT30S` 선발화가 짧은 override 를 무효화
  - R1 이 상시 승격한 음성 대조군에서 **가장 중요한 poller 정지 대조군이 빠져 있었음**(N11 과 모순)
  - P18 cold start 판정식이 warm reuse 를 구별 못 함
- 신규 항목: P19(시나리오 격리) · P20(실행 예산)

## 2026-08-27 — 계획 리뷰 라운드 3 (최종)
- 항목: 12건 (P0:0, P1:10, P2:2)
- 처리: 반영 12건 / 기각 0건 (R2 기각 1건은 **철회**)
- 뒤집힌 전제:
  - R2 #7 기각 논거가 **내 계획서 자체에 의해 반증** — 운영 URL 과 stub URL 로 값이 갈리므로 `base-url` 은 환경별 연결 정보
  - `DlqTopology` 철회가 과잉교정 — 업무 구독 21쌍은 이미 그 안에 있어 신설 시 **이중 정본**
  - `run_id` marker 분기가 **도달 불가** — warm datadir 은 initdb 스크립트를 재실행하지 않음
  - `payment.failed` 소비자는 3곳. **Payment 는 자기 이벤트를 소비하지 않아** `processed_events` 행이 생기지 않음
  - P18/P19 상호 모순 + `image-contract-lint` 가 matrix 6 을 강제
  - **`hpx_plan_lint` 실제 위반** — 등장 순서 P1..Pn 강제 + `목표/목적`·`영향 파일` 필수 섹션
- 조치: 전면 재번호 P1~P20(등장 순서), §4 영향 파일 신설, `✗` 검증 행 0으로, lint 직접 실행 통과
- 수렴 판정: **P1 = 0 이 아니므로 상한(3회) 도달로 종료.** 잔여 위험은 §9 미충족 + 이 audit 에 기록

## 2026-08-28 — 구현 (④-d-2a: P1~P9) · 실제 스택 실행 증적

**실행 결과 (docker compose E2E, 실제 4서비스 + Kafka + MySQL + PG stub)**
- readiness: **통과** — run marker 대조 · flyway 6/5/5/3 success · 앱 4 health · 토픽 20 · consumer group 28 (active member ≥ 1, 할당 partition ≥ 1)
- 시나리오 A(결제 실패 체인): **통과** (다회) — `payment_status=FAILED` · `order_status=CANCELLED` · `cancel_reason=PAYMENT_FAILED`(outbox payload) · `reservation=RELEASED` · `stock_restored=5` · `processed_events` order/product/notification 각 1 · `payment.failed`/`order.cancelled` outbox `PUBLISHED` · 알림 2행
- 시나리오 B(예약 실패 체인): **통과** (단독 실행 · A 직후 실행 각 1회) — `order_status=CANCELLED` · `cancel_reason=RESERVATION_FAILED` · `reserved_remaining=0`
- 시나리오 C(환불 체인 전구간): **통과** — fence `refund_rows=1` · `refund_status=SUCCEEDED` · `payment_status=REFUNDED` · `payment.refunded` outbox `PUBLISHED` · stub 취소 POST **정확히 1회** · Idempotency-Key 전송
- 시나리오 D(DLQ intake): **통과** — 원장 1행 · 식별자 6컬럼 non-null · `failed_consumer_group=order-svc-payment-failed-group`
- **4종 연속 실행은 불안정** — 계획 §9-9 에 미충족으로 기록

**실행이 잡아낸 결함 (전부 내 코드/기대의 오류, 운영 코드 아님)**
1. `categories` 에 `created_at` 컬럼 없음 — seed SQL 오류
2. `payments` 에 `updated_at` 컬럼 없음 — seed SQL 오류
3. 장바구니 담기 응답이 200 이 아니라 **201**
4. envelope 필드가 `data` 가 아니라 **`payload`** — 초안이 `.get("data", payload)` 로 조용히 fallback 해 `reason=None` 이 나왔고, 이는 **계약 위반처럼 보였다**(실제로는 파서 오류). 엄격 실패로 교체
5. `dead_letter_records.origin_topic` 은 `.dlq` 가 아니라 **원본 토픽**(`DLT_ORIGINAL_TOPIC`)
6. **`wait_price_cached` 가 vacuous wait** — group 이름만 보고 아무 행이나 있으면 통과해, 앞 시나리오가 남긴 행으로 **기다리지 않고 즉시 통과**했다(R2 #3 이 경고한 시나리오 간 오염이 실제로 발생). `product_id` 로 키잉해 수정
7. **가드 거부와 결제 실패를 구분하지 못했다** — `ready_for_payment` 가 서기 전에 승인을 불러 `PAY-008` 을 맞고도 "승인 실패" 로 넘겼다. 그 경로는 Toss 를 부르지도 않아 `payment.failed` 가 발행되지 않는다. `PAY-005` 명시 단언 추가
8. `stock_before` 를 주문 **후**에 읽어 비동기 예약 차감의 전/후가 불확정 → 초기 재고 기준으로 교체
9. **`TOSS_BASE_URL` 이 `application-local.yml` 의 리터럴을 이기지 못했다** — dispatcher 가 실제로 `api.tosspayments.com` 을 호출했고 **`internal: true` 가 `UnknownHostException` 으로 막았다**. 격리가 없었으면 실 PG 로 나갔다. → `TOSS_PAYMENTS_BASE_URL`(프로퍼티 직접 override)로 교체. **N2 network-level 강제의 라이브 증거이기도 하다**
10. readiness health 폴링이 `URLError` 를 안 잡아 일시적 DNS 실패 하나로 죽었다
11. `docker compose ... down` 이 `E2E_RUN_ID` 를 요구해 **정리 절차가 실패** → 잔여 스택 13개가 이후 실행과 자원을 다퉜다. compose 기본값 부여 + 강제는 wrapper 로 이동

**환경 튜닝**: 자동생성 토픽 1파티션 고정(3파티션 × 20토픽 × 28group 메타데이터 부하로 소비 3분 지연 실측) · 앱 **순차 기동**(동시 기동 시 order-service 가 360s healthcheck 창을 넘김) · 시나리오 상한 180s

**검증**: 8모듈 **800 테스트 0 실패**(792 → 신규 8) · lint **13종**(신규 `e2e-network-contract-lint` self-test 9 · `kafka-subscription-contract-lint` self-test 7 · pg-stub self-test 19)

**분할**: 사용자 승인으로 ④-d-2a(P1~P9) / ④-d-2b(P10~P20 + ④ 종결)로 나눔

## 2026-08-28 — diff 리뷰 라운드 1
- 항목: 10건 (P0:0, P1:3, P2:7)
- 처리: 반영 10건 / 기각 0건
- 주요: `.pyc` 커밋 · 시나리오 B 가 outbox 존재만 확인 · 시나리오 D 가 행수/attempt_count 미단언 ·
  flyway 검사가 '성공 1건 이상' 이라 최신 누락 미검출 · base-url 테스트가 YAML 문자열만 검사 ·
  DLQ lint 가 `DlqTopology.` 접두사만 확인 · reconcile 주기 override 가 `lockAtLeastFor=PT30S` 와 모순
- **뒤집힌 전제**: base 의 `spring.profiles.active` 가 `local` 이라 **기본 부팅은 sentinel 이 아니라
  local 의 운영 URL 을 쓴다**. 새로 붙인 ConfigData 테스트가 내 진술을 반증했다.
- raw: `.cache/codex-reviews/diff-d2a-r1.json`

## 2026-08-28 — diff 리뷰 라운드 2
- 항목: 5건 (P0:0, P1:4, P2:1)
- 처리: 반영 5건 / 기각 0건
- **1R 수정이 만든/남긴 새 결함 3건**:
  1. 1R 에서 추가한 시나리오 B 알림 대기가 **여전히 vacuous** — 같은 시나리오의 `order.created`
     소비가 만든 `ORDER_CREATED` 행으로 즉시 만족됐다(A 의 user=100 은 배제했지만 B 자신의 선행 행).
     A 도 동일 → **`type` 으로 키잉**(`PAYMENT_FAILED` / `ORDER_CANCELLED`)
  2. 1R 에서 붙인 `@Nested Resolution` 테스트가 **앰비언트 `TOSS_BASE_URL` 을 읽어** 환경에 따라
     통과/실패가 갈렸다(리뷰어가 `TOSS_BASE_URL=http://ambient.example:7777` 로 재현). systemEnvironment
     에서 그 키를 제거하는 initializer 로 격리 → 같은 환경변수를 띄우고 재실행해 통과 확인
  3. 1R 의 "정확 상수명 동등 비교" 도 **리터럴 값만 교환하면 통과**한다(참조 이름은 그대로).
     그리고 `PeekcartService.dlqListenerGroup()`/`quarantineListenerGroup()` 이 **이미 정본으로 존재**했다
     — 내 새 상수는 중복 정본이었고, 이는 R3 #3 에서 지적받은 실수의 재현이다.
     → `DlqListenerGroupContractTest` 로 상수 ↔ 정본 정합을 전 enum 에 대해 고정
- **P1 스위치 폐기(2R #4)**: `dispatch-enabled`/`reconcile-enabled` 는 설계가 stub+internal network 로
  바뀌면서 **아무도 쓰지 않게 됐는데**, 환경변수 하나로 운영 스케줄러 빈을 조용히 없애는 위험만 남았다
  (ADR-0018 은 두 잡을 미결 환불 수렴의 필수 구성요소로 규정). 프로퍼티·`@ConditionalOnProperty`·토글
  테스트를 전부 제거하고 P1 을 `base-url` 설정화로 축소했다.
- raw: `.cache/codex-reviews/diff-d2a-r2.json`

### 리뷰 수정 후 재실행 증적 (2026-08-28)
- `./gradlew test` **BUILD SUCCESSFUL** (11m 3s) · **804 테스트 0 실패**
- lint: `e2e-network-contract`(self-test 9) · `kafka-subscription-contract`(self-test **9**) ·
  pg-stub self-test 19 · 기존 10종 그린
- `TOSS_BASE_URL=http://ambient.example:7777 ./gradlew :payment-service:test --tests '*TossBaseUrlContractTest*' --rerun-tasks` → **BUILD SUCCESSFUL** (환경 격리 확인)
- E2E 재실행: **A 통과**(`notification_payment_failed=1` — type 키잉이 실제로 값을 잡았다) ·
  **B 통과**(`order_cancelled_outbox=PUBLISHED`, `notification_order_cancelled=1`, `stock_intact=1`) ·
  **C 통과** · **D 통과**(`rows=1`, `attempt_count=1`)
- **4종 연속 실행은 여전히 불안정** — 이번에도 C 직후 D 가 타임아웃했고 단독 재실행은 통과했다(§9-9)

## 2026-08-29 — diff 리뷰 라운드 3 (1차 시도: Codex 사용량 한도로 미실행 → 한도 해제 후 재실행)
- 1차 시도는 `You've hit your usage limit` 로 종료했다. 그 사이 R3 프롬프트에서 "의심하라" 고 지정했던 5개 지점을 **직접 점검**했다:
  - (a) `stripAmbientBaseUrl` 부작용 — 평범한 `MapPropertySource` 로 교체하면 **환경변수 완화 매핑이 사라져** 다른 프로퍼티 해석까지 바뀐다 → `SystemEnvironmentPropertySource` 로 복원. unchecked cast 도 제거
  - (b) 스위치 제거 후 죽은 코드 — `dispatchEnabled`/`reconcileEnabled`/`@ConditionalOnProperty` 잔존 0, `@Scheduled`/`@SchedulerLock` import 는 정상 유지
  - (c) quarantine null 분기 — `PeekcartService` 4값 전부 매핑이 있어 현재는 안전하나, **서비스 추가 시 조용한 NPE** 가 되므로 "모든 서비스가 소유 매핑을 갖는다" 계약 테스트를 추가
  - (d) type 키잉 ↔ `NotificationType` — `PAYMENT_FAILED`/`ORDER_CANCELLED` 실재 확인. 시나리오 A 는 `order.cancelled(reason=PAYMENT_FAILED)` 를 Notification 이 스킵하므로(④-b) `PAYMENT_FAILED` 키잉이 유일하게 의미 있는 조건이고, B(user 200)와 사용자도 다르다
  - (e) 계획서 정합 — §6 완료 조건이 ④-d-2 **전체**의 것이라 분할 주석을 추가
### 라운드 3 (재실행)
- 항목: 4건 (P0:0, P1:1, P2:3) · 처리: 반영 4건 / 기각 0건
- **3R 수정이 만든 새 결함 1건 (P1)**: 앰비언트 환경 격리를 `SystemEnvironmentPropertySource` 로 복원하자
  **완화 매핑 때문에 `TOSS_PAYMENTS_BASE_URL` 이 다시 유입**됐다 — 하필 **E2E compose 가 쓰는 이름**이다.
  리뷰어가 `TOSS_PAYMENTS_BASE_URL=http://ambient-relaxed.example:8888` 로 재현해 Resolution 5건 중 4건 실패.
  → 이름 열거를 버리고 **정규화 키**(대문자·`_`·`.`·`-` 제거) 비교로 교체하고 `systemProperties` 도 함께 거른다.
  같은 재현 조건 + `-Dtoss.payments.base-url=...` 으로 재실행해 통과 확인.
- 문서 정합 3건: 영향 파일 표가 폐기된 스위치를 여전히 지시 · 상단 "이 PR 이 ④ 를 종결" 이 §6 분할과 충돌 ·
  §9-5 가 "HTTP 표면을 지나지 않는다" 로 적혀 있으나 실제로는 진입점을 지난다(미검증은 gateway/`SIGNED_ONLY`) ·
  §9-8 종점을 `payment.refunded` outbox 까지로 정정 · `TossPaymentClient` Javadoc 의 "기본값이 없다" 정정
- **확인된 것**: A/B 의 `type`+`user` 조합은 겹치지 않는다 · DLQ 상수 **리터럴 값 교환**은 `PeekcartService`
  정본 대조에서 실제로 실패한다(2R #3 수정이 유효)
- raw: `.cache/codex-reviews/diff-d2a-r3.json`

### 수렴 판정
- 3라운드에서 **P0 0 · P1 1**(그 1건도 3R 수정이 만든 것) → 상한 3회 도달로 종료. **P1 = 0 은 아니다.**
- 남은 위험: 3R 수정(정규화 키 필터·문서 정정)을 제3자 리뷰로 확인하지 못했다.
  ④-d-2b 착수 시 **이 diff 를 포함해** 1라운드를 먼저 돌린다.

### 최종 검증 (2026-08-29)
- `./gradlew test` **BUILD SUCCESSFUL** (21m 12s) · **813 테스트 0 실패**
- lint **13종** 그린 — 신규 `e2e-network-contract`(self-test 9) · `kafka-subscription-contract`(self-test 9) + 기존 11종, pg-stub self-test 19
- `TOSS_BASE_URL` 앰비언트 주입 상태에서도 계약 테스트 통과(환경 격리)
- E2E: **A · B · C · D 각각 통과**(강화된 단언). **4종 연속 실행은 불안정**(§9-9)

## 2026-08-29 — /done applied (PR https://github.com/Kimgyuilli/PeekCart/pull/92)
- `docs/TASKS.md` 구현 ④ 행: **④-d-2a ✅ #92** 등재 + ④-d-2b 🔲 대기(P10~P20 + ④ 종결, 착수 시 #92 diff 포함 재리뷰 선행). ④ 자체는 **🔄 유지** — 종결은 d-2b 소관
- `docs/progress/PHASE4.md`: ④-d-2a 이력 추가(분할 근거·설계 2회 뒤집힘·정본 복제 3건 중 1건 놓침·실행이 반증한 전제·리뷰 3라운드·미충족 5건)
- **ADR**: 신규/상태 전환 없음 — 이 PR 은 새 아키텍처 결정을 만들지 않았다. `base-url` 소유는 ADR-0007 기존 결정의 적용이고, 스위치 폐기는 ADR-0018 을 **따른** 것이다
- **Layer 1(01~07) 동기화**: 계획 P20 소관이라 **④-d-2b 로 이연**(이 PR 범위 밖)

## 2026-08-29 — CI 실패 수정 (#92)
- **실패**: `payment-service:test` 의 `[SAGA-P1-BASEURL-BOOT] 기본 활성 프로파일 local 에서는 local 의 endpoint 가 이긴다` (162 중 1건)
- **원인**: CI 는 `SPRING_PROFILES_ACTIVE: test` 로 빌드하는데(`.github/workflows/ci.yml:104-106`) 그 테스트가 **앰비언트 활성 프로파일에 의존**했다. `test` 프로파일에는 `base-url` 선언이 없어 base sentinel 이 해석되고, "local 이 이긴다" 단언이 깨진다.
- **성격**: diff 리뷰 3R #1(앰비언트 `TOSS_BASE_URL` 의존)과 **같은 부류**다. base-url 계열 키는 걷어냈으면서 **활성 프로파일은 걷어내지 않았다.**
- **수정**: 격리 initializer 의 제거 대상에 `spring.profiles.active` 를 추가. 프로파일이 필요한 테스트는 각자 `withPropertyValues` 로 명시한다.
- **재발 방지 관점**: 로컬에서 `./gradlew test` 만 돌리고 **CI 의 실제 명령(`./gradlew build` + `SPRING_PROFILES_ACTIVE=test`)을 재현하지 않은 것**이 누락 지점이다. 이번엔 그 조합으로 재현 → 수정 → 재검증했다.
- **검증**: `SPRING_PROFILES_ACTIVE=test ./gradlew build --no-daemon` → **BUILD SUCCESSFUL (20m 32s)** · CI lint 블록 전량(self-test 포함) 그린 · `SPRING_PROFILES_ACTIVE=test TOSS_BASE_URL=... TOSS_PAYMENTS_BASE_URL=...` 동시 주입에서도 통과

## 2026-08-29 — CI 실패 2차 (#92, images 잡)
- **실패**: `images (payment-service)` health smoke — `Could not resolve placeholder 'TOSS_BASE_URL' in value "${TOSS_BASE_URL}" <-- "${toss.payments.base-url}"`
- **원인**: 1차 수정으로 `build` 가 통과하자 **그 뒤에 도는 `images` 잡이 처음으로 실행**됐고, 거기서 P1 이 만든 결합이 드러났다. `application-k8s.yml` 에 기본값 없는 `${TOSS_BASE_URL}` 을 넣어 **k8s 프로파일로 뜨는 모든 경로**가 값 주입 전까지 부팅 실패한다 — `docker-health-smoke.sh:105` 가 `SPRING_PROFILES_ACTIVE=k8s` 로 띄우고 `TOSS_SECRET_KEY`/`TOSS_WEBHOOK_SECRET` 만 주입한다.
- **판단 오류**: k8s 매니페스트가 `TOSS_*` 를 비워 두는 건 그것이 **자격증명**이기 때문이다(`secret.yml:12-15` 가 명시). `base-url` 은 endpoint 이고, 같은 파일이 `datasource.url`·`redis.host` 를 **리터럴로 선언**한다 — 규약을 잘못 읽고 자격증명 쪽에 붙였다.
- **수정**: `base-url: ${TOSS_BASE_URL:https://api.tosspayments.com/v1}` — **누락될 값 자체를 없앴다**(env override 유지). 계약 테스트도 "미주입 시 부팅 실패" → "미주입이어도 실 PG 로 해석" 으로 정정.
- **영향 범위 재점검**: 실제 k8s 배포도 같은 이유로 깨졌을 것이다(operator 가 `TOSS_BASE_URL` 을 주입할 이유가 없었다). 이번 수정이 그것도 함께 해소한다.
- **검증**: `bash scripts/docker-health-smoke.sh payment-service:smoke` → **passed** (CI 와 같은 스크립트) · `SPRING_PROFILES_ACTIVE=test ./gradlew build` → **BUILD SUCCESSFUL (8m 52s)** · 프로파일+base-url env 동시 주입에서도 계약 테스트 통과

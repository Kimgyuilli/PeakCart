# Phase 4 진행 보고서 — MSA 분리

> Phase 4 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md`, 설계·실행 로드맵은 `docs/progress/phase4-design-roadmap.md` 참고

---

## Phase 4 목표

ADR-0002 의 "모놀리식 → MSA 진화" 4단계 중 최종 단계. 5개 서비스 분해 + Gateway + Choreography Saga + CQRS.

**Exit Criteria** (`07-roadmap §16`):
- [ ] 모든 서비스 독립 배포 및 정상 동작 확인
- [ ] Saga 보상 트랜잭션 플로우 검증 (결제 실패 → 주문 취소 → 재고 복구)
- [ ] Gateway 라우팅 및 JWT 인증 정상 동작
- [ ] 서비스 간 직접 호출 없이 이벤트 + 로컬 캐시로 데이터 조합 확인

> 설계 단계(A1~A4) → 구현 단계(①~⑥). 상세 시퀀싱: `phase4-design-roadmap.md`.

---

## 작업 이력

### 2026-06-13 ~ 06-14

#### A1 — ADR-0010 서비스 분해 (설계)

**완료 항목**:
- **ADR-0010** 신규 (`docs/adr/0010-phase4-service-decomposition.md`, Status: Accepted) — §5(5개 풀 분해) 정본 비준, §4-5(3개 드리프트 목록) 정정. 5개 서비스 경계 표(D1) + 이벤트 토폴로지(D2, 토픽 4개) + Choreography Saga 체인(D3) + Phase 4 Exit Criteria coverage matrix(D4)
- 비준 시 추가 도식 정합 3건 (ADR-0010 §C4):
  - **F1** Notification DB 소유 확정 → `02-architecture.md §5` DataLayer 에 NotificationDB 추가, `05-data-design.md` 와 정합
  - **F2** 재고 차감 소유 트랜잭션 경계 충돌(Order 단일 트랜잭션 ↔ Product 재고 소유) 기록 → 재고 예약/차감 경계는 A3 위임
  - **F3** CQRS 로컬 캐시용 `product.updated`(Product→Order) 이벤트 필요 명시 → 스키마는 A3
- Layer 1 정합: `02-architecture.md §4-5`(5개로 정정 + `see ADR-0010`), `03-requirements.md §7-2`(Saga 재고 복구 주체 Order→Product 정정), `05-data-design.md`(Notification DB 정합 표기)
- `docs/adr/README.md` INDEX 행 추가

**설계 결정**: 서비스 경계 = §5 정본(5개). 근거·대안(Alt A 5개 vs Alt B 3개)은 ADR-0010.

**프로세스**: `/plan` 2회 Codex 리뷰(1차 5건, 2차 3건 전체 반영) → `/work` 구현 → `/ship` ([PR #44](https://github.com/Kimgyuilli/PeakCart/pull/44)). 계획서·audit: `docs/plans/task-adr0010-service-decomposition.md`.

**다음**: A2(멀티모듈 구조) — `common` 경계·의존 규칙. ADR-0010 §D1 의 5개 서비스 = 5개 모듈.

#### A2 — ADR-0011 멀티모듈 구조 (설계)

**완료 항목**:
- **ADR-0011** 신규 (`docs/adr/0011-phase4-multimodule-structure.md`, Accepted) — `common` + **`peekcart-common-observability`(ADR-0009 선결정)** + 5개 서비스 모듈. 모듈 레이아웃(D1) + class-level common 경계(D2) + 의존 규칙·위반 검출 필수(D3) + 빌드/테스트/이미지 계약(D4)
- 핵심 결정: 서비스는 `:common`+`:peekcart-common-observability` 만 의존, 서비스↔서비스 직접 의존 금지(CI 빌드 실패 검출). 이벤트 DTO 는 모듈 소유만, 스키마는 A3 위임(non-authoritative). Docker health smoke 서비스별 유지
- Layer 1 정합: `02-architecture.md §4-4`(관측성/5서비스 모듈 + `see ADR-0011`), §12(Phase 4 멀티모듈 포인터), `adr/README.md` INDEX

**프로세스**: `/plan` **3회** Codex 리뷰(1차 5건, 2차 2건[ADR-0009 모듈 충돌 발견], 3차 1건[자기모순 cleanup] — 5→2→1 수렴) → `/work` 구현(diff 리뷰 2건) → `/ship` ([PR #45](https://github.com/Kimgyuilli/PeakCart/pull/45)). 계획서·audit: `docs/plans/task-adr0011-multimodule-structure.md`.

**다음**: A3(DB-per-service + 이벤트/Saga 계약) · A4(Gateway 보안) — 병렬 가능. 이후 구현 ①(멀티모듈 전환).

#### A3 — ADR-0012 DB-per-service + 이벤트/Saga 계약 (설계)

**완료 항목**:
- **ADR-0012** 신규 (`docs/adr/0012-phase4-db-event-saga-contract.md`, Accepted) — DB-per-service domain/infra 경계(D1) + 이벤트 스키마(D2: envelope `schemaVersion`·파티션키·`product.updated` 필드·`OrderCancelled` items 보강) + 재고 예약 Saga(D3) + 토픽×producer×consumer×group 매트릭스(D4) + retention=멱등성 창 상한(D5)
- 핵심 결정: F2 해소 — Product 재고 소유로 Order 직접 차감 불가 → **예약 모델**(`order.created → Product 예약 → stock.reservation.result → 결제`). 예약 실패 신호용 **신규 토픽 `stock.reservation.result`** 채택(옵션 B). ADR-0010 4토픽 → 6토픽 refine. retention ≥ max(topic retention, consumer 다운타임, DLQ 수동 재처리 창, backfill)
- Layer 1 정합: `05`(Product DB outbox/processed/예약 컬럼), `04`(§9-6 전략 A→예약 모델, §9-4 Saga, §16 product.updated), `03 §7-2`(예약 경계), `02 §5`(토폴로지 6토픽), `adr/README.md`
- 편입 부채: L-008/L-011(retention), L-020-2(consumer group 라벨)

**프로세스**: `/plan` **3회** Codex 리뷰(1차 6건, 2차 1건, 3차 0건 — 6→1→0 수렴) → `/work` 구현(diff 리뷰 3건) → `/ship` ([PR #46](https://github.com/Kimgyuilli/PeakCart/pull/46)). 계획서·audit: `docs/plans/task-adr0012-db-event-saga-contract.md`.

**다음**: A4(Gateway 보안) — 마지막 설계 ADR. 이후 구현 ①(멀티모듈 전환).

#### A4 — ADR-0013 Gateway 보안 (설계, 마지막 설계 ADR)

**완료 항목**:
- **ADR-0013** 신규 (`docs/adr/0013-phase4-gateway-security.md`, Accepted) — RS256 전환(D1, Gateway 공개키 1차 검증·서비스 미재검증·JWKS·kid/alg allow-list·키 overlap) + 시크릿 저장소 3안(D2, Secret Manager 기본/KMS 격상) + Spring Cloud Gateway(D3, 검증 순서·헤더 신뢰 모델·route-class Rate Limit·fail-closed) + Reuse Detection(D4, `family_id` 이력 모델 + 탈취 containment) + 인증 실패 관측성(D5, ADR-0009 S9 surface 추가)
- 핵심 결정: 대칭키 공유 제거(RS256), reuse 감지 시 family 무효화 + access token `family_id` 클레임/family deny 로 이미 발급된 토큰까지 Gateway 차단
- Layer 1 정합: `04 §10-2/§9-2`, `03 §7-2`, `05 refresh_tokens`(family_id/status/grace_until), `02 §5`, `adr/0009`(S9 행 추가), `adr/README.md`
- 편입 보안 묶음: L-001(RS256)/L-002(시크릿)/L-003(Reuse Detection)/L-019(관측성)

**프로세스**: `/plan` **3회** Codex 리뷰(1차 8건, 2차 1건, 3차 0건 — 8→1→0 수렴) → `/work` 구현(diff 리뷰 4건) → `/ship` ([PR #47](https://github.com/Kimgyuilli/PeakCart/pull/47)). 계획서·audit: `docs/plans/task-adr0013-gateway-security.md`.

**다음**: 🎯 초기 설계 ADR(A1~A4) 완료. **구현 단계 ①(Gradle 멀티모듈 전환)** 부터 — 실제 코드. (구현 ① PR2 착수 중 전환기 인증 보정 ADR-0014 추가 — 아래 A4.5)

#### A4.5 — ADR-0014 전환기 인증 검증 공유 모듈 (보정 ADR, 구현 ① PR2 중 발견) — ✅ [PR #50](https://github.com/Kimgyuilli/PeakCart/pull/50)

**배경**: 구현 ① PR2(서비스 분리) 착수 중, ADR-0011 §D2(auth=User전속)가 게이트웨이(ADR-0013, 구현 ③) 전제였음이 드러남. 게이트웨이 이전엔 5개 서비스 전부 JWT 검증 필요(Product 도 `AdminProductController @PreAuthorize` admin API).

**완료 항목**:
- **ADR-0014** 신규 (`docs/adr/0014-transitional-auth-module.md`, Accepted) — 전환기 JWT **검증** 전용 모듈 `peekcart-common-auth` 도입. D1 모듈 경계(검증 모듈 vs User 발급/blacklist write) + D1-b JwtProvider sign/verify 분리 + D1-c Blacklist/Deny Redis Contract + D2 전환기 결합(대칭키·블랙리스트 fail-closed)·게이트웨이 exit(제거/잔류 분리) + D3 의존규칙(5개 서비스 의존, 모듈 7→8)
- **ADR-0011 → Partially Superseded by ADR-0014** (§D1 토폴로지 확장 + §D2 검증 소유 + §D3 allowlist, 발급/User 소유는 유효)
- Layer 1 정합: `02-architecture §4-4`(모듈 목록 +auth), `adr/README`, 상위 `task-impl1` 계획(7→8모듈·PR2 인증 메모)

**핵심 결정**: 검증→`peekcart-common-auth`, 발급/블랙리스트 write→User, 블랙리스트 read=공유 Redis+fail-closed. "라이브러리 공유 ≠ 런타임 중앙화(게이트웨이가 그것)".

**프로세스**: `/plan` **3회** Codex 리뷰(1차 5건, 2차 3건, 3차 1건 — 5→3→1, P0 전 라운드 0; 3차가 Product auth-free 오류 포착) → `/work`(ADR 작성). 계획서·audit: `docs/plans/task-adr0014-transitional-auth-module.md`.

**다음**: 구현 ① PR2a(Notification) — `peekcart-common-auth` 생성 + 첫 서비스 peel.

---

## 구현 단계 (①~⑥) — 코드

> 초기 설계 ADR(A1~A4, #44~#47) + 보정 ADR-0014(A4.5)가 SSOT. 각 구현 항목은 선행 ADR을 따라 PR 단위로 진행하며(구현 ①은 ADR-0011/ADR-0014), 세부 PR 분할은 `/plan` 착수 시 정의한다.

### (진행 중) ① Gradle 멀티모듈 전환 — 선행 ADR-0011

- 단일 모듈 → `common` + `peekcart-common-observability` + 5개 서비스 모듈 (ADR-0011 §D1)
- 의존 위반 검출 Gradle task(서비스↔서비스 금지), testFixtures 재배치, Dockerfile/CI matrix/k8s N개화
- 편입 부채: L-016a(gke `newTag` digest 고정), D-016(GHCR→AR image promotion 자동화)
- ⚠️ 대규모 리팩토링 — work diff 大, 실제 빌드/테스트 동반. **3-PR 분할 확정**(PR1 스켈레톤+common → PR2 서비스 5개 분리 → PR3 Dockerfile/CI/k8s). 계획서: `docs/plans/task-impl1-gradle-multimodule.md`.

#### PR1 — 멀티모듈 스켈레톤 + common/observability 추출 ✅ ([#48](https://github.com/Kimgyuilli/PeakCart/pull/48))

**완료 항목** (P1~P6):
- 루트 `build.gradle` 멀티모듈화(`allprojects`/`subprojects` 공통 설정: Spring BOM·Java 17·lombok·test platform), root 는 과도기 app 유지
- `settings.gradle` → `common` + `peekcart-common-observability` include
- `common` 모듈(`java-library`) — `entity`/`response`/`exception`/`kafka`/`filter.MdcFilter`/`lock`/`cache`/`outbox.dto`/`config.{RedisConfig,RedissonConfig}` 이동(전부 `git mv` R100, 패키지 경로 유지)
- `peekcart-common-observability` 모듈 — `MetricsConfig`(S1) 이동 (ADR-0009 인용)
- `java-test-fixtures` — 의존-깨끗 support 4종(`AbstractIntegrationTest`/`ServiceTest`/`WithMockLoginUser`*) 재배치
- 검증: `./gradlew build` BUILD SUCCESSFUL (3모듈 + 51 test 그린, Testcontainers 포함)

**구현 중 발견 → PR2 이연**: `WebMvcConfig`/`OpenApiConfig`(→auth/User)·`KafkaConfig` 팩토리(→`SlackPort`/Notification)·`SecurityConfig` S4·S3 yml·`IntegrationTestConfig`·`fixtures` 가 서비스 전속 코드에 컴파일 의존 → ADR-0011 §D3 "common→서비스 의존 금지" 불변식상 PR2 로 이연. 계획서 P3/P4/P5/P10 갱신.

**프로세스**: `/plan`(Codex 2회: 9건→4건, P0 0) → `/work`(diff 리뷰 1회 P2 1건, 자동 통과) → `/ship` ([PR #48](https://github.com/Kimgyuilli/PeakCart/pull/48)).

**다음**: PR2(서비스 5개 모듈 분리 — P7~P13).

#### PR2a-1 — common-auth 추출 + JWT 검증/발급 분리 ✅ ([#51](https://github.com/Kimgyuilli/PeakCart/pull/51))

> PR2a 가 예상보다 커서 **PR2a-1(common-auth 추출, 본 PR)** 와 PR2a-2(notification peel) 로 분할. 서비스 peel 없이 인증 검증 토대만 마련.

**완료 항목** (P7/P10 부분, ADR-0014 D1):
- `peekcart-common-auth` 모듈 신설 — 검증 primitives 9종(LoginUser/CurrentUser/resolver/TokenClaims/TokenParseException/JwtFilter/handler 2종/WebMvcConfig/OpenApiConfig) 이동(`git mv` R100)
- **`JwtProvider` → `JwtTokenVerifier`(common-auth) / `JwtTokenSigner`(root, User 발급) 분리** (D1-b) + `JwtAuthProperties`(`app.jwt.*`) 단일 설정 계약 → sign·verify 동일 secret 바인딩
- **`TokenBlacklistPort`(write, User) ↔ `TokenBlacklistLookupPort`(read, common-auth) 분리** + `RedisTokenBlacklistLookupAdapter`(`miss=pass`/`Redis 실패=fail-closed`, D1-c). jti/hash+namespace 마이그레이션은 PR2c 이연
- `JwtSecurityConfigurer` 재사용 기여 → root `SecurityConfig` 가 단일 `SecurityFilterChain` 생성, `AuthService` 검증 재배선
- root `build.gradle` `:peekcart-common-auth` 의존(전환기 4서비스 잔류)

**구현 중 발견 수정 (systemic, 멀티모듈)**:
- 라이브러리 모듈(boot 플러그인 부재)에 `-parameters` 미적용 → Spring 생성자 by-name DI(`RedisTemplate` 동명 빈 `NoUniqueBean`) 깨짐 → root `subprojects` 에 `-parameters` 추가
- 동일 모듈에 `junit-platform-launcher` 부재 → 테스트 실행 불가(`TestSuiteExecutionException`) → `subprojects` `testRuntimeOnly` 추가

**구현 중 발견 → PR2a-2 이연**: `SlackPort` 가 notification 도메인뿐 아니라 **root `OutboxPollingService`·`KafkaConfig.kafkaErrorHandler`(order/payment DLQ→Slack)** 에서도 사용되고 유일 구현체 `SlackNotificationClient` 가 notification 내부에 있음. plan T7/ADR-0011 §D2 "SlackPort→Notification 전속" 분류 오류 → PR2a-2 착수 시 `:common` 이동 vs 복제 결정 필요(ADR-0011 §D2 정정 동반).

**검증**: `./gradlew build` 그린 — **272 root tests + 7 common-auth tests**(new: RedisTokenBlacklistLookupAdapterTest 3 / JwtFilterTest 4).

**프로세스**: `/plan`(Codex 3회: 5→3 신규, P0 0, fail-closed 게이트 보강) → `/work`(diff 리뷰 1회 P1 1건 — common-auth 단위 회귀 추가) → `/ship` ([PR #51](https://github.com/Kimgyuilli/PeakCart/pull/51)).

**다음**: PR2a-2(notification peel) — **선결: SlackPort 경계 결정**. 이후 PR2b(Product)/PR2c(User)/PR2d(Order+Payment)/PR3.

#### PR2a-2a — SlackPort → :common 횡단 인프라 ✅ ([#52](https://github.com/Kimgyuilli/PeakCart/pull/52))

> PR2a-2(notification peel)의 선결 SlackPort 경계 결정을 별도 체크포인트로 분리. 서비스 peel 없음.

**완료 항목** (N1·N2):
- **`SlackPort`(인터페이스, `global.port` 경로 유지) + `SlackNotificationClient`(→ `com.peekcart.global.slack`) → `:common` 이동** — 횡단 인프라(root outbox/kafka DLQ alerting + notification 공용). client 는 notification 도메인 의존 0, `:common` 의 `spring-boot-starter-web`(api) `RestClient` 충족
- **ADR-0011 §D2 표 SlackPort 행 정정**(서비스 전속→common) + **Update Log** 추가 — 사실 오류 정정(`fix(adr):`), 신규 ADR 아님. plan T7/§D2 분류가 코드 현실(+SlackPort javadoc "횡단 관심사")과 어긋났던 것

**핵심 결정**: SlackPort 는 "Notification 전속"이 아니라 **횡단 인프라(common)**. SlackPort javadoc·실제 사용처(outbox/kafka DLQ)가 이미 횡단성을 증언. 인터페이스 패키지 경로 유지로 8개 사용처 무변경 → root 알림 경로 무회귀.

**검증**: `./gradlew build` 그린 (272 tests). Codex diff 리뷰 P0:0/P1:0/P2:1(계획 본문 모순 정정).

**프로세스**: `/plan` **3회**(SlackPort 경계 검증 — 2→2→2 신규, P0 0; ADR Update Log vs 신규 ADR 판단·KafkaConfig §D2 경계·회귀 게이트 구체화) → `/work`(N1/N2, diff 리뷰 P2 자동통과) → `/ship` ([PR #52](https://github.com/Kimgyuilli/PeakCart/pull/52)).

**다음**: PR2a-2b(notification peel, N3~N9) — 새 브랜치. notification-service 모듈/도메인 이동/KafkaConfig 분리/flyway/assertNoServiceProjectDeps/테스트/Dockerfile.

#### PR2a-2b — notification-service peel (첫 서비스 분리) ✅ ([#53](https://github.com/Kimgyuilli/PeakCart/pull/53))

> 단일 모놀리스(root)에서 **첫 마이크로서비스(`notification-service`)를 독립 모듈/`bootJar`/독립 부팅으로 분리**. 선행: ADR-0011 §D1~D4, ADR-0014(전환기 JWT 검증), ADR-0009 S4.

**완료 항목** (N3~N9):
- **N3·N4** `notification-service` 모듈(`bootJar`) + `settings.gradle` include · `NotificationApplication`(`com.peekcart` base, 공유 `global.*` 스캔) + 서비스 yml 3종(`application=notification-service`, `app.jwt.*`, flyway disabled) · 도메인 `git mv` · **공유 `db/migration`→`:common` 단일 소유**(전 모듈 classpath+테스트 fixture 접근)
- **N5** idempotency 복제(소비자 멱등성) · `NotificationKafkaConfig`(listener/error-handler+DLQ→`:common` SlackPort) · thin `NotificationSecurityConfig`(common-auth `JwtSecurityConfigurer` 배선) · **observability `ActuatorSecurityConfig`(ADR-0009 §Decision S4 단일 소유 실현)** + root SecurityConfig 정렬 · `IntegrationTestConfig`→`:common` testFixtures(root도 사용)
- **N6** root `flywayMigrateShared` task(Flyway 11.7.2 플러그인) — 과도기 공유 DB 단일 마이그레이션 실행 지점, 서비스 런타임 flyway disabled
- **N7** `assertNoServiceProjectDeps`(allowlist 외 project 의존 빌드 실패, doLast 평가) + 의도적 위반 재현
- **N8** notification 통합 3종(consumer 멱등성·보안 negative+blacklist+cross-module JWT+chain·관측성) + root 부팅 스모크(SlackPort=:common SlackNotificationClient) + `assertNoDuplicateGlobalFqcn`(게이트 i) + **root Idempotency/Dlq/Outbox 테스트 디커플**(NotificationConsumer peel → root-observable 효과 기준 재작성)
- **N9** Dockerfile COPY 컨텍스트 동기화 + 로컬 `docker build`(root 이미지) 검증

**핵심 결정**:
- **전환기 인증(ADR-0014)**: 검증은 `:peekcart-common-auth`, 각 서비스 thin SecurityConfig 가 `SecurityFilterChain` 1개 생성. cross-module JWT 는 동일 `app.jwt.secret`/HS256 계약(게이트 h).
- **actuator 단일 소유(ADR-0009 S4)**: `ActuatorSecurityConfig.mergedPublicUrls()` 가 actuator permitAll 유일 정의처 — 서비스/root 는 비즈니스 PUBLIC_URLS 만 선언(과허용 회귀/드리프트 차단). 코드 리뷰(N5)에서 "서비스별 actuator 재기재 금지" 지적 반영.
- **root 테스트 디커플**: NotificationConsumer 가 root 에서 peel → root Idempotency/Outbox/Dlq 통합테스트가 notification 도메인 의존하던 부분을 Payment/Order/Inventory + 테스트 전용 Kafka listener 로 재작성. notification 소비 검증은 notification-service 로 이관.

**검증**: `./gradlew build` 그린(8모듈, 264+ tests, Testcontainers) · `assertNoServiceProjectDeps`/`assertNoDuplicateGlobalFqcn` + 위반 재현 · 로컬 `docker build` 성공 · 게이트 a~j 충족.

**프로세스**: `/plan` 3회(N3~N9 보강 — JWT 키 `app.jwt.*` 정정·actuator S4 소유권·게이트 i/c 매핑·게이트 j root 회귀; 2차 타임아웃→3차 lean 재시도) → `/work`(split 리뷰 c1~c3 aggregate=ok, P1:3/P2:3 → 수용 3 dep-check 평가시점·PUBLIC_PATHS private·테스트 .get() / 보류 3 기존 패턴) → `/ship`([PR #53](https://github.com/Kimgyuilli/PeakCart/pull/53), 6 커밋).

**다음**: PR2b(Product) / PR2c(User, 발급 owner) / PR2d(Order+Payment, root 소멸) / PR3(Dockerfile per-service·CI matrix·k8s N개화).

---

## PR2b — user-service peel (independent, 발급 owner) — [PR #55](https://github.com/Kimgyuilli/PeakCart/pull/55)

> 두 번째 서비스 분리. 선행: ADR-0011 §D1~D4, ADR-0014(발급=User/검증=common-auth), ADR-0009 S2/S4.
> **peel 순서 정정(roadmap §2)**: Product 가 Order 의 동기 `ProductPort` 빈에 묶여 ① 단독 peel 불가 → independent 한 User 를 먼저 분리, Order/Product/Payment 는 ②/④/⑤ 교차 사가 클러스터로 이연(ADR-0010 F2·ADR-0012 D3, 새 ADR 불필요).

**완료 항목** (U1~U9):
- **U1·U2·U3** `user-service` 모듈(`bootJar`, `KafkaAutoConfiguration` 제외) · `UserApplication` + yml 3종(`application=user-service`, `app.jwt.*`, flyway disabled) · `com.peekcart.user.*` 도메인 + 발급 issuer global(`JwtTokenSigner`/`TokenIssuer`/`TokenBlacklistPort` write) `git mv`(`com.peekcart.global.*` 패키지 유지, 중복 FQCN 금지) · **root 발급 경로 완전 제거**
- **U4** thin `UserSecurityConfig`(common-auth `JwtSecurityConfigurer` + `@EnableMethodSecurity` + `PasswordEncoder` 빈 이관) · root `SecurityConfig` 에서 `passwordEncoder()`·`/api/v1/auth/**` 라우트 제거
- **U5** 블랙리스트 namespace 마이그레이션 — 신키 `auth:blacklist:<sha256hex>`(원문 미저장, ADR-0014 D1-c) write + 전환기 **dual-read**(legacy `bl:<token>` access TTL 동안 read) · `TokenHasher`(:common-auth 단일 소유)
- **U6·U7** 과도기 flyway disabled(공유 `flywayMigrateShared` 재사용) · `assertNoServiceProjectDeps`/`assertNoDuplicateGlobalFqcn` 에 user-service 포함
- **U8** user-service 통합(관측성 S2/S4 · 보안 negative: 미인증 401·공개 endpoint 201·신키/legacy blacklist hit·cross-module JWT·chain 1개·actuator permitAll) + common-auth dual-read 단위 4종 + **root 테스트 디커플**(User 도메인 peel → Outbox/Idempotency 통합테스트 users 행 native SQL 시드, `UserFixture`→user-service)
- **U9** Dockerfile COPY 컨텍스트 동기화 + 로컬 `docker build`(root) 검증

**핵심 결정**:
- **`:common` 횡단 빈 처분(blindspot B6)**: `com.peekcart.*` 스캔으로 새 서비스가 :common @Component 를 떠안음 → `SlackNotificationClient`(필수 `@Value`)는 `@ConditionalOnProperty(slack.webhook.url)` 로 Slack 사용 모듈만 로드, `KafkaAutoConfiguration` exclude, 비전이 starter(`validation`)는 명시 선언. (`PLAN-BLINDSPOTS.md` B6 추가)
- **U5 token-hash dual-read**: jti 미도입(현 토큰에 jti 부재 → 발급/검증/claims 변경 회피) → `token-hash` 고정. read 측 dual-read 로 마이그레이션 중 legacy 차단 토큰 누출 차단.

**검증**: `./gradlew build` 그린(root 234·user 40·notification 20·common-auth 8, 0 fail, Testcontainers) · `assertNoServiceProjectDeps`/`assertNoDuplicateGlobalFqcn` · 로컬 `docker build` · 게이트 a~j 충족.

**프로세스**: `/plan`(Codex 2회: PasswordEncoder 누락 P0·U5 dual-read·U1 Kafka 봉합·신키 token-hash 고정 등 13건 반영) → `/work`(Codex diff 1회 P0/P1:0, P2 #1 SlackNotificationClient 조건부·#2a 공개 endpoint 테스트 반영) → `/ship`([PR #55](https://github.com/Kimgyuilli/PeakCart/pull/55), 7 커밋).

**다음**: Order/Product/Payment 사가 클러스터(②/④/⑤ 교차) / PR3(Dockerfile per-service·CI matrix·k8s N개화).

---

## 사가 클러스터 strangler-1 — 재고 예약/복구 이벤트화 — [PR #56](https://github.com/Kimgyuilli/PeakCart/pull/56)

> Order/Product/Payment 사가 클러스터의 첫 strangler 단계(②/④ 교차, peel 없음). 선행: ADR-0010 F2(재고 차감 경계), ADR-0012 D3/D4(예약 choreography·`stock.reservation.result`·§50 payload·§65 all-or-nothing·§46 envelope). **새 ADR 불필요** — 기존 ADR 가 결정 보유, 바뀌는 건 실행·임시 의미뿐.
> **임시 호환 단계**: D3 최종 2-phase 모델이 아니라 D3 경로를 여는 임시 단계. `reserved=true`="이미 차감됨". 단가는 임시 동기 `getUnitPrice`(strangler-2 에서 `product.updated` 캐시로 대체), Payment charge 예약 게이트는 strangler-3.

**완료 항목** (P1~P8):
- **P1** Order 동기 재고변경 전면 제거 — `ProductPort.decreaseStockAndGetUnitPrice`→`getUnitPrice`(read-only), `restoreStock` 인터페이스 제거. createOrder 차감 제거, cancel/cancelExpired/handlePaymentFailed 복구 제거(이벤트로 이관).
- **P2** 계약 — `:common` `StockReservationResultPayload`/`ReservedItemPayload`, `KafkaEventEnvelope` `schemaVersion`(4-인자 호환 생성자 + 누락→v1 정규화 `@JsonCreator`), `KafkaConfig` `stock.reservation.result`(+dlq) 토픽.
- **P3** 예약 원장 — `stock_reservations`(V5) orderId 상태머신(RESERVED/CANCEL_REQUESTED/RELEASED/FAILED) + `order_id`/`source_event_id` UNIQUE, `RESERVED→RELEASED` 원자 CAS(JPQL @Modifying).
- **P4** `ProductOutboxEventPublisher`(공유 outbox 재사용, aggregateId=orderId, MdcSnapshot trace 전파).
- **P5** Product 예약 consumer(`order.created`) — tombstone skip + **all-or-nothing**(전 품목 선검사 후 일괄 차감, race 시 PRD-002 전파→롤백→재시도 수렴) + 멱등.
- **P6** Product release consumer(`order.cancelled`+`payment.failed`) — `RESERVED→RELEASED` CAS 가드 복구(double-release 방지) + cancel-before-create tombstone.
- **P7** Order 결과 consumer(`stock.reservation.result`) — reserved=false→취소+`order.cancelled`(이미 CANCELLED 면 멱등 no-op), reserved=true→`reservationConfirmedAt`(V6) 기록 / `OrderTimeoutScheduler` 예약 미확정 PENDING 수렴(조기취소 방지).
- **P8** 단위(StockReservationService·OrderEventConsumer 가드·envelope) + 서비스레벨 통합(happy·all-or-nothing·취소순서·double-release·cancel-before-create, 실 DB CAS/마이그레이션) + **root 테스트 디커플**(`OutboxKafkaIntegrationTest` payment.failed→취소만, `DlqIntegrationTest` order.created 다중 consumer DLQ).

**핵심 결정**:
- **예약 원장 = orderId 상태머신 테이블** (2차 plan 리뷰 P0#1·P1#2·P1#4 수렴): `processed_events` 만으론 cross-topic 순서(취소 선도착)·서로 다른 eventId 의 double-release 를 못 막음 → tombstone + 원자 CAS 로 해소.
- **all-or-nothing = 선검사 후 일괄 차감**(REQUIRES_NEW 곡예 회피): 부분 차감은 race 시 PRD-002 전파로 전체 롤백, 재시도 시 선검사가 차단.
- **빈 items 거부**(work 리뷰 c3:1)·**reserved=false on already-CANCELLED no-op**(work 리뷰 c1:1)·**누락 schemaVersion→v1 정규화**(c1:3).

**검증**: `./gradlew build` 전체 그린(Testcontainers 포함).

**프로세스**: `/plan`(Codex 2회: 1차 8건[P0×2 스코핑 구멍] → 전면 스코프 확장, 2차 5건[원장 상태머신 수렴] 전체 반영) → `/work`(split diff 리뷰 c1~c3 aggregate=ok, P1:4/P2:3 → 수용 4: cancel-guard·빈items·schemaVer·표적테스트 / 반려 2: chunk false-positive·DLQ 의도적 트레이드오프) → `/ship`([PR #56](https://github.com/Kimgyuilli/PeakCart/pull/56), 5 커밋).

**다음**: strangler-2(단가 `product.updated` 로컬 캐시 → `getUnitPrice` 제거) → strangler-3(2-phase 확정/해제 commit + Payment charge 예약 게이트, pay-before-result) → Product→Order+Payment peel(② DB 분리 동반) / PR3.

---

## 사가 클러스터 strangler-2 — 단가 로컬 캐시 CQRS — [PR #57](https://github.com/Kimgyuilli/PeakCart/pull/57)

> 사가 클러스터 두 번째 strangler 단계(⑤ CQRS 로컬 캐시 교차, peel 없음). strangler-1 이 임시로 남긴 동기 `ProductPort.getUnitPrice`(주문 트랜잭션 내 Product 단가 동기 read)를 choreography CQRS 로 대체. 선행: ADR-0012 ⑤(Product 변경 이벤트 구독·Order 내 캐시)·:47/:48(파티션키=productId·필수 7필드)·:46(envelope)·D5(retention), ADR-0010 F3. **새 ADR 불필요**.
> 범위 결정: **단가만**. `verifyProductExists`(장바구니 검증)는 동기 `ProductPort` 로 의도적 잔존(완전 제거는 strangler-3/Product peel).

**완료 항목** (P1~P5):
- **P1** `product.updated` 계약+발행 — `:common` `ProductUpdatedPayload`(ADR-0012:48 필수 7필드 + 순서키 `version`), Product `@Version`, `ProductOutboxEventPublisher.publishProductUpdated`(aggregateId=productId·status 매핑·`ProductRepository.saveAndFlush` 후 version), `ProductCommandService` create/update/delete(discontinue) 발행, `KafkaConfig` `product.updated`(+dlq) 토픽.
- **P2** Order 로컬 가격 캐시 — Flyway V7(`product_price_cache`(product_id PK·unit_price·source_version·updated_at) + `products.version` ALTER + seed), `ProductPriceCache` 엔티티 + repository(`order/domain`) + JPA/Impl(`order/infrastructure`).
- **P3** `ProductPriceCacheConsumer`(`product.updated`, group `order-svc-product-updated-group`) — `IdempotencyChecker` 멱등 + `version` stale-skip, price·version 만 소비.
- **P4** `ProductPort.getUnitPrice` 제거(`verifyProductExists` 잔존), `ProductPortAdapter` 동일. `OrderCommandService` 가 `ProductPriceCacheRepository` 로 단가 read, 미스 시 `ORD-007`(동기 fallback 없음).
- **P5** 단위(발행자 7필드+version·status 매핑, OrderCommandService 캐시 read·미스, ProductCommandService 발행) + 통합(Testcontainers: create→발행→캐시·flush 경계 v0→v1·stale-skip·멱등·schemaVersion 호환·**e2e relay→@KafkaListener→createOrder OrderItem 스냅샷**·캐시미스 ORD-007).

**핵심 결정**:
- **순서 키 = Product `@Version` monotonic version** (plan 라운드2 #1): `OutboxEvent.eventId`(랜덤 UUID v4)는 사전순≠인과순서라 tie-breaker 부적합 → `@Version` 채택. 파티션키=productId(in-order) + version 비교 stale-skip.
- **flush 경계** (plan 라운드3 #1): `@Version` 은 flush 시 증가 → payload version 은 `saveAndFlush` 후 `getVersion()` 으로 읽음(seed=0 ↔ 첫 이벤트=0 충돌 회귀 방지, 통합테스트로 가드).
- **원자 upsert** (work 리뷰 #1): 2-step update→insert 의 `save()` UK 위반이 catch 밖(commit)에서 터져 "높은 version 만 적용"이 깨짐 → `INSERT ... ON DUPLICATE KEY UPDATE ... IF(:version>source_version,…)` 단일 원자 문장으로 교체.
- **payload = ADR-0012:48 7필드 전체**(축소 금지, status `ON_SALE→ACTIVE` 등 매핑), Order 캐시는 price·version 만 소비.
- **캐시 미스 = ORD-007 명시 실패**: seed + create-발행으로 정상경로 미스 제거, 잔여 경합만 명시적 실패(seam 완전 제거 — 동기 fallback 두면 getUnitPrice 잔존).

**검증**: `./gradlew test` 전체 BUILD SUCCESSFUL(가격캐시 통합 7/7). `getUnitPrice` 제거는 인터페이스 컴파일 가드(ArchUnit 인프라 부재).

**프로세스**: `/plan`(Codex 3회: 1차 4건[payload 계약 위반·tie-breaker·문구·retention] → 2차 4건[랜덤UUID→@Version·status 매핑·discontinue·seed] → 3차 1건[@Version flush 경계] 전체 반영) → `/work`(diff 리뷰 1회 P1:1/P2:1 → #1 원자 upsert·#2 e2e 테스트 전체 반영) → `/ship`([PR #57](https://github.com/Kimgyuilli/PeakCart/pull/57), 5 커밋). 부산물: PLAN-BLINDSPOTS B7(버전-가드 upsert 원자성 + @Version flush 경계).

**다음**: strangler-3(2-phase 확정/해제 commit + Payment charge 예약 게이트, `verifyProductExists` 처리) → Product→Order+Payment peel(② DB 분리 동반) / PR3.

---

## 사가 클러스터 strangler-3 — 2-phase 예약 확정/해제 + 결제 게이트 — [PR #58](https://github.com/Kimgyuilli/PeakCart/pull/58)

> 사가 클러스터 세 번째 strangler 단계(④ Saga commit/보상 교차, peel 없음). 선행: ADR-0010 F2, ADR-0012 D3/④(payment.completed→예약 확정, commit-실패=환불 요청+운영 알림). **새 ADR 불필요**.
> 범위 결정: **2-phase 확정/해제 + 결제 게이트만**. `verifyProductExists` 캐시 전환·Product peel 은 후속.

**완료 항목** (P1~P7):
- **P1** 원장 `CONFIRMED` 종결 상태 + `confirmed_at`/`compensated_at` 컬럼(Flyway V8). `status VARCHAR(20)` 라 enum 추가는 DDL 불요.
- **P2** `markConfirmedIfReserved`(RESERVED→CONFIRMED)·`markCompensatedIfAbsent`(보상 1회성) 원자 CAS — `markReleasedIfReserved` 미러.
- **P3** `StockReservationService.confirm(orderId)` 상태 분기: RESERVED→CONFIRMED commit / CONFIRMED 멱등 no-op / **원장 없음=transient → throw(consumer 재시도)** / RELEASED·CANCEL_REQUESTED·FAILED → 보상.
- **P4** commit-실패(PAID_BUT_UNRESERVED) 보상 — 원장 `compensated_at` `orderId` 1회성 marker + 최초 1회 `SlackPort` 운영 알림(자동 환불 미존재 → 수동).
- **P5** `StockConfirmConsumer`(`payment.completed`, group `product-svc-payment-completed-group`) → confirm. release 와 의미 분리 위해 별도 consumer.
- **P6** 결제 게이트 — `Order.markPaymentRequested()`(전이불가 ORD-003 → 미확정 ORD-008(409) → 통과 시 `paymentRequestedAt` 기록), `OrderPortAdapter` 교체. 타임아웃 기준 `orderedAt`→`paymentRequestedAt`(V9 + 기존 PAYMENT_REQUESTED 행 backfill, `findExpiredPaymentRequested` null 폴백).
- **P7** 단위(confirm 분기·게이트 ORD-008/003·결제 게이트 전파) + 통합(payment.completed→CONFIRMED·확정후 release 보호·역순 race 보상·보상 멱등·confirm×2+release×1 동시성 수렴·consumer e2e·JPQL null 폴백).

**핵심 결정**:
- **race 를 막지 않고 검출+보상** (plan 라운드1 P0): confirm(`payment.completed`)과 release(`order.cancelled`/`payment.failed`)는 별도 토픽이라 무순서. confirm-우선 가정 대신, 확정 시점에 원장이 RELEASED 등이면 commit-실패로 검출해 보상으로 수렴(ADR-0012 ④). CONFIRMED 종결성으로 확정 후 지연 release 는 CAS 자연 no-op(판매분 보호).
- **타임아웃 기준 = paymentRequestedAt** (plan 라운드1 P1): `orderedAt` 기준은 생성 15분 경과 주문 결제 시 진행 중 취소 race → 결제 요청 시점 기준 전환 + 기존 행 backfill/null 폴백으로 마이그레이션 회귀 방지.
- **보상 멱등 = 원장 compensated_at CAS** (plan 라운드2 P1): 신규 테이블 대신 `orderId` 1회성 컬럼 CAS — DLQ 재발행(새 eventId, `processed_events` 우회) 에도 알림 1회.
- **게이트 분류**: 전이 검사(ORD-003 영구) 우선 → 예약 확정 검사(ORD-008 409 retryable). HttpStatus 가 곧 API 재시도 계약.

**검증**: `./gradlew test` 전체 BUILD SUCCESSFUL. 동시성(confirm×2+release×1)이 `CONFIRMED+복구0+보상0` 또는 `RELEASED+복구1+보상1` 한쪽으로만 수렴 확인.

**프로세스**: `/plan`(Codex 2회: 1차 5건[P0×2 ADR-0012 ④ 보상경로·cross-topic race / P1×2 타임아웃 race·검증 / P2 ORD 분류] 전체 반영 → 2차 4건[V9 backfill·confirm retry 의미·보상 멱등 키·동시성 테스트] 전체 반영) → `/work`(diff 리뷰 1회: 1차 180s 타임아웃→480s 재시도, aggregate=ok P0:0/P1:0/P2:3 테스트 갭 전체 반영) → `/ship`([PR #58](https://github.com/Kimgyuilli/PeakCart/pull/58), 5 커밋).

**다음**: `verifyProductExists` 캐시 전환 → Product→Order+Payment peel(② DB 분리 동반) / PR3.

---

## 사가 클러스터 strangler-4 — `verifyProductExists` 로컬 캐시화 — [PR #61](https://github.com/Kimgyuilli/PeakCart/pull/61)

> 사가 클러스터 마지막 strangler 단계(⑤ 로컬 캐시 활용, peel 없음). Order→Product 에 유일하게 남은 동기 호출(`ProductPort.verifyProductExists`, 장바구니 추가 검증)을 제거해 Order↔Product production 동기 결합을 0 으로 만든다. 선행: ADR-0010 F2(동기 결합 지목), ADR-0012 ⑤(로컬 캐시 CQRS). **새 ADR 불필요** — roadmap §58.
> 범위 결정: **seam 제거만**. Product→Order+Payment peel(② DB 분리 동반)·full `product_cache`(name/status/stock)와 장바구니 조회 CQRS 조합은 후속.

**완료 항목** (P1~P5):
- **P1** `ProductPriceCacheRepository.existsByProductId` 추가(`@Id`=productId → `existsById` 위임). 별도 스키마 변경 없음.
- **P2** `CartCommandService.addItem` 재배선: `ProductPort.verifyProductExists` → 로컬 캐시 존재성. 캐시 미스(미존재 or `product.updated` 전파 전)는 신규 `ORD-009`(409, 재시도) 로 거절. `ProductPort` 의존 제거.
- **P3** `ProductPort`(order.application.port) + `ProductPortAdapter`(product.infrastructure.adapter) 삭제 → 두 패키지 디렉토리 소멸.
- **P4** 단위(히트 성공/미스 ORD-009)·통합(캐시미스→addItem ORD-009 재작성, 캐시히트 e2e 회귀 가드)·컨트롤러(409/code/message 단언) 테스트.
- **P5** `assertNoOrderProductSourceCoupling` custom Gradle source-scan 가드(src/main 한정) + `check` 연결.

**핵심 결정**:
- **존재성 = 로컬 가격 캐시 존재성**: `product_price_cache`(strangler-2, V7 이 기존 상품 전량 seed)에 있으면 주문 가능. cold-start gap 없음. addItem 의 eventual-consistency 창은 `createOrder` 의 가격 미스(ORD-007) 창과 대칭 — 새 위험 아님.
- **ORD-009 (외부 계약 변경)**: addItem 미스 응답이 404(PRD-001)→409(ORD-009, 재시도 시맨틱). ORD-007(가격)과 분리. 컨트롤러 슬라이스 테스트로 계약 고정 + `04-design-deep-dive` 에러코드 표 반영.
- **경계 가드 = source-scan (ArchUnit 미도입)**: 기존 `assertNoServiceProjectDeps` 패턴 따라 새 의존성 없이 `src/main` order↔product 상호 참조 금지. `src/test` 의 합법적 Product 타입 시드는 제외(production 한정).

**검증**: `./gradlew assertNoOrderProductSourceCoupling test` BUILD SUCCESSFUL(7m20s). seam 잔존(src/main+src/test)·order↔product FQCN 경계 전부 0건 → Order↔Product production 동기 결합 소멸(Product peel 선행조건 충족).

**프로세스**: `/plan`(Codex 2회: 1차 4건[P1×2 ORD-009 외부계약+CartControllerTest·grep 검증 강화 / P2×2 BLINDSPOTS 처분·peel-ready 스코프] 전체 반영 → 2차 2건[ArchUnit→source-scan 가드 전환·production 한정] B안 반영) → `/work`(diff 리뷰 1회: aggregate=ok, P0:0/P1:0/P2:1 — 계획 P2 범위 내 04-design 누락분 반영) → `/ship`([PR #61](https://github.com/Kimgyuilli/PeakCart/pull/61), 5 커밋).

**다음**: Product→Order+Payment peel(② DB 분리 동반·V7 seed → product.updated replay 전환) / PR3.

---

## Product peel — product-service 모듈 분리 (첫 *발행* 서비스) — [PR #62](https://github.com/Kimgyuilli/PeakCart/pull/62)

> 사가 클러스터 strangler 완결(Order↔Product 동기 결합 0) 이후 Product 도메인을 독립 `product-service` 모듈로 peel. notification(#53)·user(#55) peel 선례를 잇는 **첫 *발행* 서비스 분리**. 선행 ADR-0010 F2·ADR-0011·ADR-0012·ADR-0014. **새 ADR 불필요**.
> 범위 결정(사용자 게이트): **모듈 peel 만. DB 물리 분리는 범위 외** — user/notification 선례대로 공유 root DB·Flyway runtime disabled 유지, FK 유지. 실 DB-per-service(FK drop·별 datasource)는 5개 서비스 peel 후 ②로 일괄.

**완료 항목** (P1~P8):
- **P1~P2** product-service 모듈 신설(settings/build.gradle) + Product 도메인 `git mv`(이력 보존) + ProductApplication(@EnableScheduling).
- **P3** root 전속 `global.outbox` 실행 7종 + `global.idempotency` 5종 + `ShedLockConfig` **복제**(`:common`은 payload DTO만 보유 → 발행 서비스 전속, notification idempotency 복제 선례), `CacheConfig` 이관.
- **P4** 공유 DB poller 소유권 분리: `OutboxEventJpaRepository.findPendingEvents(aggregateTypes,…)`+`countByStatusAndAggregateTypeIn` allowlist, `@SchedulerLock name=${app.outbox.lock-name}` → root=`ORDER,PAYMENT`/`rootOutboxPollingJob`, product=`PRODUCT`/`productOutboxPollingJob`. 발행 경로 + backlog gauge 양쪽 분리.
- **P5** product-service 횡단 배선: `ProductSecurityConfig`(@EnableMethodSecurity, @PreAuthorize 구동)·`ProductKafkaConfig`·Cache/Redis/Slack·application*.yml.
- **P6~P7** Product 테스트 13개+ProductFixture 이관(B3 단일 소비자), root 통합테스트 4종 디커플(native-insert 시드·payload 직접 주입·findPendingEvents 시그니처), 관측성 products-cache 검증 product-service 이관.
- **P8** 경계 가드: `assertNoDuplicateGlobalFqcn` *-service 동적 구성·`assertNoOrderProductSourceCoupling` 스캔 경로 갱신.

**핵심 결정**:
- **outbox/idempotency 복제 vs 공통 이관**: 복제 채택(notification 선례·guard 가 다른 앱 classpath 공존 허용). 공통 추상화는 root 까지 건드리는 큰 리팩터라 peel 범위 밖.
- **공유 DB poller 경합 차단**: 복제 poller 가 같은 `outbox_events` 를 보므로 aggregateType allowlist + ShedLock 이름 분리로 자기 도메인 이벤트만 발행·집계(Codex 2차 plan-review P1).
- **DB 분리 범위 외**: 물리 분리는 5개 서비스 peel 후 일괄(②)이 FK drop/baseline 을 한 번에 정리해 안전.

**검증**: 전체 멀티모듈 `./gradlew build` BUILD SUCCESSFUL(7m45s, fresh) + 가드 3종 통과(assertNoDuplicateGlobalFqcn 동적 product-service 포함). product-service bootJar 산출.

**프로세스**: `/plan`(Codex 2회: 1차 4건[P1×3 outbox 실행세트 복제 누락·테스트 7→13·가드 하드코딩 / P2 검증 강화] 전체 반영 → 2차 1건[공유 DB poller 경합] 반영) → `/work`(빌드 2회 적발: string-level 결합 4부류[락이름·URL·캐시·aggregateType] + 이관 통합테스트 flyway → 수정 / diff 리뷰 P2 2건[gauge 소유권 누수·발행 검증 갭] 반영) → `/ship`([PR #62](https://github.com/Kimgyuilli/PeakCart/pull/62), 4 커밋). 부산물: PLAN-BLINDSPOTS B1b(역의존 스윕은 FQCN import 외 string-level 식별자도 쓸어라).

**다음**: Order↔Payment 동기 결합 제거(strangler) → Order+Payment peel(root app 소멸) → PR3.

## 사가 클러스터 strangler-5 — Order↔Payment 동기 결합 제거 (peel 선행) — [PR #63](https://github.com/Kimgyuilli/PeakCart/pull/63)

> Order+Payment peel 의 **선행 strangler**. Product 가 ProductPort 동기 빈으로 peel 불가였던 것과 동형으로, **Payment 가 Order 의 동기 빈 `OrderPort`**(`verifyOrderOwner`·`transitionToPaymentRequested`)에 묶여 두-모듈 peel 불가였다. 모놀리스 안에서 seam 을 이벤트+payment-로컬 상태로 제거해 Order↔Payment src/main 상호 참조 0 달성. 실제 peel·root 소멸은 후속 PR-B. 선행 ADR-0010·ADR-0012·ADR-0014, **새 ADR 불필요**(GP-1 — ADR-0012 §D4 refine 으로 흡수).

**완료 항목** (P1~P8):
- **P1** Flyway expand-contract: V10(payments user_id/version/ready, nullable+backfill)·V11(orders payment_requested_pending)·V12(payment_cancellations). user_id NOT NULL 은 후속 V13+(코드 배포·lag 0 확인 후).
- **P2** Seam 1: `verifyOrderOwner` → `Payment.verifyOwner()`(payment-로컬 userId, PAY-007). `order.created` payload 의 userId 를 Payment 에 저장.
- **P3** Seam 2: `transitionToPaymentRequested` → `payment.requested` 이벤트 발행(+KafkaConfig topic/DLQ). Order 가 소비해 PAYMENT_REQUESTED 전이.
- **P4** 게이트 payment-로컬 복원: reserve→pay(`stock.reservation.result`→`ready_for_payment`, PAY-008)·취소(`order.cancelled`→`cancelBeforePayment`, PAY-009)·`@Version` 동시성·선도착 수렴(order pending marker + payment_cancellations marker)·APPROVED-후-취소 SlackPort 알림(§D3 ④).
- **P5~P6** `OrderPort`/`OrderPortAdapter` 삭제 + 가드 `assertNoOrderPaymentSourceCoupling` 신설.
- **P7** ADR-0012 §D4 refine: payment.requested 행 + order.cancelled Payment consumer + 게이트 이전 노트.
- **P8** 단위/슬라이스 테스트(도메인 게이트·consumer·발행·이벤트 역전·동시성).

**핵심 결정**:
- **게이트 2조건 payment-로컬 분해**: 동기 `markPaymentRequested`(PENDING + reservationConfirmedAt) 게이트를 reserve(ready 플래그)·취소(로컬 status) 2조건으로 복원. 잔여 lag race 는 §D3 ④ 보상 수렴(회귀 0 아닌 "수렴").
- **이벤트 역전 누수 0**: payment.requested 선도착 → order 영속 marker(confirmReservation 수렴); order.cancelled 선도착 → payment_cancellations 영속(Payment 생성 시 CANCELLED 적용) — DLQ 초과에도 silent-charge 누수 0(throw-retry 만으로 불충분, work 2차 리뷰 발견).
- **DB 분리 범위 외**: peel·root 소멸·DB 물리 분리는 후속.

**검증**: `./gradlew :build` BUILD SUCCESSFUL(통합+가드 3종, 4회 그린). order↔payment src/main 상호 참조 0(신규 가드).

**프로세스**: `/plan`(Codex 3회: 1차 6건[P0 비동기창·reserve→pay·D4·backfill 등] → 2차 4건[V11/V12 expand-contract·선도착·CAS·D4 group] → 3차 3건[marker 영속·V11 경계·topic/DLQ] 전체 반영) → `/work`(diff 리뷰 2회: 1차 4건[역전·보상연결·Flyway순서·consumer테스트] → 2차 2건[**영속 cancellation marker**·계획서 정정]) → `/ship`([PR #63](https://github.com/Kimgyuilli/PeakCart/pull/63), 4 커밋). 부산물: PLAN-BLINDSPOTS **B9**(이벤트 역전 게이트 누수 — 종료/취소 이벤트가 생성 이벤트 선도착 시 영속 marker 필요).

**다음**: Order+Payment peel(root app 소멸, 본 strangler 계획서를 입력으로) → PR3(Dockerfile/CI/k8s). 이후 ② DB 물리 분리 일괄.

---

## Order peel PR-a — order-service 모듈 분리 ([#64](https://github.com/Kimgyuilli/PeakCart/pull/64))

> 마지막 두 도메인 Order/Payment peel 의 2 PR 중 **PR-a (Order peel)**. root 는 Payment+global 유지·계속 부팅. 선행 ADR-0010/0011/0012/0014 (새 ADR 불필요 — 경계/구조/이벤트 결정 보유).

**완료 항목** (P1~P8):
- **P1·P2** `order-service` 모듈 신설 + order 도메인(`com.peekcart.order.*`) 43파일 → order-service(git mv). `OrderApplication`(@EnableScheduling — outbox poller·OrderTimeoutScheduler 구동).
- **P3** outbox 실행세트 7 + idempotency 5 + ShedLockConfig 를 root→order-service **byte-identical 복제**(root 는 payment 위해 유지, product peel 선례). `OrderKafkaConfig` — **producer-owns-topic**: `order.created`/`order.cancelled`(+`.dlq`) NewTopic 4 + listener factory/error-handler.
- **P4** root outbox poller allowlist `ORDER,PAYMENT`→`PAYMENT`(order 자가발행 → 공유 DB 3 poller disjoint: order/payment/product).
- **P5** `OrderSecurityConfig`(검증 전용·발급 아님) + data-redis **무조건**(ADR-0014 common-auth blacklist fail-closed) + `OrderApplicationTests` 부팅 스모크.
- **P6** order 테스트 16 + OrderFixture + OutboxPollingServiceTest 이관. presentation 테스트 `SecurityConfig`→`OrderSecurityConfig`.
- **P7** root 통합테스트 4개 payment-observable 재작성 — `OutboxKafka`/`Idempotency`(cross-service 결합 제거, KafkaTemplate 직접 produce)·`ObservabilityMetrics` probe `ORDER`→`PAYMENT`·`RootContextBootSmoke` `/api/v1/orders`→`/api/v1/payments`.
- **P8** 가드(`assertNoOrderPaymentSourceCoupling`·`assertNoOrderProductSourceCoupling`) order 스캔경로 → order-service.

**핵심 결정**:
- **producer-owns-topic (NewTopic 분산)**: 1차 plan 의 "order-service 전 토픽 단독 owner" 가 ADR-0011/0012 발행-서비스-전속 원칙과 충돌(plan 2차 리뷰) → order=order.*·payment=payment.*·product=product.* 로 분산. PR-b 에서 payment/product NewTopic 신설.
- **root 테스트 디커플 = payment-observable**: order↔payment 소비 플로우가 cross-service 가 되어 단일 root 컨텍스트로 검증 불가 → root 통합테스트는 payment 발행/소비로 재작성, order-specific 검증은 order-service 로 이관(메모리 `service_peel_root_test_decouple` 선례).
- **DB 미분리**: 모듈 경계만. root 소멸·DB 물리분리는 PR-b/②.

**검증**: `:order-service:test`(132+3) · `:test`(root payment+global) · 가드 4종(`assertNoDuplicateGlobalFqcn` order-service 자동편입·복제 FQCN 중복 0) · 전체 compile — 전부 그린. order-service 독립 컨텍스트 부팅(Redis blacklist·단일 SecurityFilterChain·Kafka listener 4종).

**프로세스**: `/plan`(Codex 2회: 1차 7건[Redis 무조건·Toss·NewTopic owner·B5 cold-start·@EnableScheduling·3-poller harness·테스트 수량] → 2차 3건[ObservabilityMetrics probe·NewTopic producer 분산·02-arch 토픽 동기화] 전체 반영) → `/work`(diff 리뷰 1회 2건[OutboxPollingServiceTest allowlist·OrderApplicationTests 스모크] 반영) → `/ship`([PR #64](https://github.com/Kimgyuilli/PeakCart/pull/64), 5 커밋). 부산물: PLAN-BLINDSPOTS **B10**(@SpringBootTest 통합테스트를 flyway-disabled 서비스 모듈로 옮기면 per-test flyway override 필요 — SchemaManagementException).

**다음**: **PR-b — Payment peel + root 해체**(P9~P18: payment-service 분리·root src 삭제·boot앱→aggregator·잔여 global 테스트 rehome·B5 런타임 마이그레이션 소유권·product-service NewTopic 신설) → PR3(Dockerfile/CI/k8s).

---

## Payment peel + root 해체 PR-b — 5개 서비스 풀 분해 완료 ([#65](https://github.com/Kimgyuilli/PeekCart/pull/65))

> Order/Payment peel 의 2 PR 중 **PR-b (마지막)**. payment 도메인을 payment-service 로 떼고 **root 모놀리스 app 을 해체**한다. ADR-0010 §5 의 5개 서비스 풀 분해 달성 — root 는 빌드/가드 aggregator 로 전환.

**완료 항목** (P9~P18):
- **P9·P10** `payment-service` 모듈 + payment 도메인 26파일 → payment-service(git mv). `PaymentApplication`(@EnableScheduling) + yml(PAYMENT allowlist·Toss placeholder 이관).
- **P11** outbox/idempotency/ShedLock 복제. `PaymentKafkaConfig`(payment.* NewTopic 6). **product-service NewTopic 4 신설**(root 무임승차 해소). **root `global/*`·`PeekcartApplication`·`src/` 전체 삭제** → root src 0 files.
- **P12** `PaymentSecurityConfig`(webhook 공개) + `PaymentApplicationTests` 부팅 스모크.
- **P13** **order-service Flyway 런타임 migrator 승계**(B5 — root 역할 인수).
- **P14·P15** payment 테스트 9 + 통합 5(payment-observable·ShedLock `paymentOutboxPollingJob`·ObservabilityMetrics `application=payment-service`) → payment-service. 유닛 5(SUT=:common) → `:common` test. RootContextBootSmoke 삭제. 전 @SpringBootTest flyway override(B10).
- **P16** **root build.gradle: boot앱 → aggregator**(bootJar disabled·런타임 deps 제거·가드 payment 경로 retarget).
- **P18** `02-architecture.md` 토픽 6→7(`payment.requested`)·producer-owns-topic 동기화.

**핵심 결정**:
- **root = 빌드 aggregator**: 5개 서비스 독립 모듈화로 root src 가 비어, bootJar 비활성·런타임 deps 제거. `./gradlew build` = 5 서비스 bootJar 산출 + root SKIPPED.
- **공유 스키마 migrator 승계(B5 신규 블라인드스팟)**: root app 소멸로 런타임 Flyway 적용 주체 상실 → order-service 가 승계(기존에도 타 서비스는 root 선마이그레이션 의존). cold-start 순서/readiness 는 PR3.
- **producer-owns-topic 완성**: product-service 가 root 무임승차하던 자기 토픽 생성 책임 인수(NewTopic 4).
- **root 단일 이미지 사망**: root app 소멸로 Dockerfile(`COPY src/`·`app.jar`)·CI Docker smoke 가 깨짐 → 제거(메모리 multimodule_dockerfile_context 예측). per-service 이미지는 PR3.

**검증**: `:common:test`·`:order-service:test`(migrator)·`:payment-service:test`(9+통합5+스모크)·`:product-service:test`(NewTopic) 그린 · 가드 4종(5 서비스·FQCN 중복 0) · `build -x test` = 5 서비스 bootJar·root SKIPPED · root src 0 files.

**프로세스**: `/work`(diff 리뷰 1회 2건[**root Dockerfile/CI 사망 봉합**·02-arch payment.* 정정] 반영) → `/ship`([PR #65](https://github.com/Kimgyuilli/PeekCart/pull/65), 7 커밋). 부산물: PLAN-BLINDSPOTS **B10**(@SpringBootTest 를 flyway-disabled 서비스 모듈로 이동 시 per-test flyway override 필요).

**다음**: 구현 ① 잔여 = **PR3(Dockerfile/CI/k8s — per-service 이미지·CI 매트릭스)**. 이후 ② DB 물리 분리(order-service 전환기 migrator 정리)·③~⑥.

## PR3a — 서비스별 Dockerfile + CI 이미지 + image-contract-lint ([#66](https://github.com/Kimgyuilli/PeakCart/pull/66))

> 구현 ① PR3(배포 표면 per-service 재구성) 의 첫 조각. 단일 `peekcart` 전제의 이미지/CI 를 서비스별로 재구성한다. k8s 매니페스트는 PR3b, 관측성 재설계+ADR-0015 는 PR3c 후속. 계획서 `docs/plans/task-impl1-pr3-dockerfile-ci-k8s.md`.

**완료 항목** (P1·P2·P3 — 3축 단일 계획 중 PR3a):
- **P1** 단일 `Dockerfile` + `ARG SERVICE`(멀티모듈 COPY 8모듈·`:${SERVICE}:bootJar`·base 이미지 digest 고정 L-016a). 5개 서비스 `docker build` 검증.
- **P2** `ci.yml` 이미지: `images`(build/smoke·contents:read) + `publish`(main push 한정·packages:write) **job 분리**. smoke 이미지를 `docker save`→artifact→`load` 로 publish 전달(재빌드 0). `docker-health-smoke.sh` 공유 스키마 선행 마이그레이션 훅(flyway 11.7.2@digest 이미지).
- **P3** `image-contract-lint.sh` per-service(D-015): canonical 5서비스 고정·`images`/`publish` matrix 일치·서비스별 base/gke 3-way. 전환기는 `IMAGE_CONTRACT_TRANSITION=1` SUSPENDED.

**핵심 결정**:
- **단일 Dockerfile + ARG**(B5): 5 서비스를 1 Dockerfile 로 — COPY 표류를 단일 지점화. settings.gradle 모듈 변경 시 동기 + 로컬 docker build 검증(memory: multimodule_dockerfile_context).
- **smoke 마이그레이션(Codex GP-2)**: 비-order 서비스(Flyway disabled+validate)가 빈 DB 에서 죽지 않도록 smoke 가 앱 전에 공유 스키마(V1~V12)를 적용. **마이그레이션 정본 = 공식 flyway Docker 이미지** — root gradle `flywayMigrateShared` 가 깨져 있음(flyway 플러그인 mysql DB 플러그인 미해석). 런타임 migrator(order-service, Spring Boot Flyway)는 정상.
- **D-015 canonical 앵커**: lint ground-truth 를 CI matrix 가 아닌 고정 5서비스로 — CI matrix 를 자기 ground-truth 로 쓰면 "서비스 축소 false-green" 순환. images↔publish matrix 드리프트도 검출.
- **digest(L-016a/D-016)**: base·flyway 이미지 digest 고정 + publish 가 push 후 registry digest 산출(비면 실패).

**검증**: 5/5 `docker build` · notification-service smoke(profile k8s·flyway V1~V12·`/actuator/health` 200) · image-contract-lint 두 모드(차단/SUSPENDED)·canonical matrix 일치 · kustomize-namespace·servicemonitor-selector lint 그린(k8s 미변경).

**프로세스**: `/plan`(Codex 리뷰 5건[smoke 마이그레이션·ADR-0015 신규·alert lint·secret 표·cold-start initContainer] 반영, GP-1 으로 ADR-0009→ADR-0015 supersede 결정) → `/work`(diff 리뷰 **3 라운드** 수렴: image-contract-lint false-green 을 checked==0→부분-매니페스트→canonical matrix 드리프트 3중으로 봉합, ci.yml job 분리, digest 강제) → `/ship`([PR #66](https://github.com/Kimgyuilli/PeakCart/pull/66), 2 커밋).

**후속 부채**: `flywayMigrateShared` gradle 태스크 수복(또는 폐기) — D- 승격 검토. cold-start initContainer·k8s per-service 매니페스트는 PR3b, 관측성 재설계·ADR-0015 작성은 PR3c.

**다음**: **PR3b**(k8s base/overlays per-service: 5 Deployment/Service/ConfigMap/Secret/ServiceMonitor·initContainer cold-start·image-contract-lint 전환기 flag 제거) → **PR3c**(관측성 per-service: alert by-clause·dashboard `$application` 변수·observability lint 2종 재활성·ADR-0015 작성+ADR-0009 Partially Superseded).

## PR3b — k8s base/overlays per-service 재구성 ([#67](https://github.com/Kimgyuilli/PeakCart/pull/67))

> 구현 ① PR3 둘째 조각. 단일 `peekcart` 를 전제하던 k8s 배포 표면을 5서비스 per-service 로 재구성한다. 관측성 alert/dashboard 재설계·ADR-0015 는 PR3c. 선행 ADR-0004/0005/0006/0007/0010/0014(새 ADR 불필요).

**완료 항목** (P4~P9·P14):
- **P4·P9** `k8s/base/services/<svc>/{deployment(+Service)·configmap·secret·servicemonitor}` ×5. 비-order 4서비스 deployment 에 `wait-for-order-migration` initContainer(curl digest 고정, `order-service` readiness 폴링 — 공유 DB 전환기 cold-start ordering). `base/kustomization` 20 리소스.
- **P5** ConfigMap/Secret per-service. product configmap `PEEKCART_CACHE_ENABLED`(D-002 토글, product 전용). **Slack 게이팅**: `:common SlackFallbackConfig` no-op 빈(`@ConditionalOnMissingBean`+`@ConditionalOnProperty(slack.noop-fallback.enabled)`) ↔ real(`@ConditionalOnProperty(slack.webhook.url)`) 상호배타. notification=k8s no-default webhook fail-fast, product/order/payment=base noop-fallback. payment Toss k8s no-default fail-fast.
- **P6·P7** minikube/gke overlay per-service strategic-merge patch ×10씩. gke `images[]` 5 entry(AR rewrite)·**order-service 단일 HPA**(GP-2 #4·로드맵 §16, 5균일 기각). gke README per-service 갱신.
- **P8** `servicemonitor-selector-lint` canonical **count==5 강제**(0개 vacuous-green 차단)·`image-contract-lint` **full 5/5**(ci.yml `IMAGE_CONTRACT_TRANSITION` 제거).
- **P14** D-016 `promote-images.sh`(GHCR→AR 승격·crane/docker·AR digest 산출+`kustomize edit set image @digest` 명령 출력·dry-run/help).

**핵심 결정**:
- **Slack presence-based 함정 제거(GP-2 loop1~3 핫스팟)**: `@ConditionalOnProperty(name=...)` 가 placeholder 기본값에 항상 매치돼 fail-fast·no-op 둘 다 깨지던 것을 base yml 기본값 정리 + 명시 property 상호배타로 봉합. notification fail-fast(silent 알림 유실 방지)·나머지 no-op. ADR-0007 정합(noop-fallback=base 동작정책·webhook/Toss=프로파일 자격증명).
- **자격증명 fail-fast(work GW-2 #2/#3)**: committed Secret 에 SLACK/TOSS placeholder 미포함(operator/external 주입) → 렌더 산출에 stub 누출 0. `docker-health-smoke.sh` 가 그 주입을 dummy 런타임 값으로 시뮬레이션(렌더엔 안 샘).
- **DB 인프라 secret 분리(work GW-2 P0)**: 단일 `peekcart-secret` 분해로 MySQL `secretKeyRef` dangling → `infra/mysql/secret.yml`(mysql-secret). B1 스윕이 놓친 infra→app-secret 간선(→ PLAN-BLINDSPOTS B1b 반영).
- **cold-start = order-service Boot Flyway 정본**(깨진 root `flywayMigrateShared` 재사용 금지). DB 미분리(② 이연) — initContainer 는 전환기 처분.

**검증**: `kubectl kustomize` minikube/gke 렌더(5 Deployment/Service/ConfigMap/Secret/ServiceMonitor·1 HPA)·lint 3종(namespace·image-contract full 5/5·servicemonitor count==5)·**notification+payment 이미지 build+smoke**(fail-fast+dummy e2e 200)·`./gradlew build` 전체(5서비스+통합테스트) 그린·`SlackPortConfigTest` 4 케이스·promote help/dry-run.

**프로세스**: `/plan`(Codex 3 loop: 7→4→2건 수렴, Slack 게이팅 반복 핫스팟·B6 함정²·B1b 신설) → `/work`(diff 리뷰 2 loop: 1차 P0:1[mysql-secret]+P1:3[SLACK/TOSS placeholder·promote digest]+P2:1 → 2차 0건 수렴) → `/ship`([PR #67](https://github.com/Kimgyuilli/PeakCart/pull/67), 5 커밋). 부산물: PLAN-BLINDSPOTS **B6 함정²**(presence-based 조건+기본값)·**B1b**(infra→공유리소스 이름 간선).

**후속 부채**: PR3c(관측성 per-service 재설계+ADR-0015 작성+ADR-0009 Partially Superseded)·D-016 full lint-digest 강제(렌더 산출 @sha256 필수)·`flywayMigrateShared` 수복.

**다음**: **PR3c** → 구현 ① 종료 → ② 서비스별 DB 물리 분리(order-service 전환기 migrator·initContainer 정리).

## PR3c — 관측성 per-service 재설계 + ADR-0015 ([#68](https://github.com/Kimgyuilli/PeakCart/pull/68))

> 구현 ① PR3 **마지막 조각**. 단일 `application=peekcart`/`service=peekcart` 를 전제하던 관측성 표면(alert/dashboard/observability lint 2종)을 per-service 로 재설계하고 ADR-0015 로 명문화. 본 PR 머지로 **구현 ① PR3(배포 표면 per-service) 전체 종료**.

**완료 항목** (P1~P6):
- **P1** **ADR-0015 신규**(`docs/adr/0015-observability-per-service-contract.md`) + ADR-0009 `Partially Superseded by ADR-0015`(Status 헤더만, 본문 불변) + README INDEX + CLAUDE.md SSOT 줄 보강. **범위 정정(Codex plan #2)**: ADR-0009 §Decision "Phase 4 owner" 컬럼은 이미 per-service 결정제 → ADR-0015 는 뒤집기 아닌 **현-위치 서술/D5-V1·V2 모놀리스 전제/S5 단일경로 정정 + 비준**(무효화 범위 명시).
- **P2** `grafana-alerts.yml` 8 rule per-service: high-error-rate/slow-response = 5서비스 정확일치 regex `application=~"..."` + `by (application)`(ratio 보존), target-down = `count by (service)`, scrape-absent = **5서비스 equality matcher rule 분할**(`absent()` by-clause 불가, ground truth = k8s Service `metadata.name`). annotation per-service 식별(`{{ $labels.application/service }}`).
- **P3** api-jvm·kafka-lag dashboard `$application` **custom 5서비스 변수**(Codex work #1 — `up{}` 엔 application 라벨 부재로 `label_values(up)` 빈 드롭다운 버그 → custom 고정) + panel query `application=~"$application"`. pod-resources 는 namespace 기반(대상 외).
- **P4** `observability-ssot-lint.sh` per-service: 5서비스 `<svc>-service/application.yml` 정본(`EXPECTED_SERVICES` 고정) + D5-V2 태그값=모듈명 + MeterFilter owner=`peekcart-common-observability/.../MetricsConfig.java`.
- **P5** `observability-promql-lint.sh` 재작성: application set(5)·Service name set(5) ground truth, `by(application)` coverage 강제(단일 equality 실패), 필수 alert uid 8개 존재 검증, scrape-absent namespace 검사, **promtool PromQL syntax**(미설치 시 exit 2, balance 대체 금지).
- **P6** `ci.yml` lint 2종 재활성 + promtool 설치 step + 단일 `peekcart` 라벨 sweep 가드(escaped-quote + service 양쪽).

**핵심 결정**:
- **ADR-0015 = 비준, 뒤집기 아님**: ADR-0009 §Decision per-service owner 결정은 유효. 무효화는 모놀리스 현-위치 서술(root yml·`base/services/peekcart`·`application=peekcart` 회귀검증)·D5-V1/V2 단일 yml 전제·S5 단일 경로로 한정(README immutable 정합).
- **dashboard 변수 = custom 고정**(work #1): `up{}` series 의 라벨은 scrape 메타(namespace/service/pod)뿐 — `application` 은 Micrometer 앱 메트릭 전용. `label_values(up, application)` 은 빈 드롭다운 런타임 버그 → 5서비스 custom 고정(ADR-0015 정본 일치).
- **lint false-green 3중 봉합**(work #2/#3/#4): `EXPECTED_SERVICES` 5 정본 == 발견집합 선검증(glob 축소 차단)·필수 alert uid 존재 검증(rule 삭제 차단)·scrape-absent namespace 검사(타 NS 가림 차단).
- **scrape-absent ground truth = Service `metadata.name`**(plan #2·work #2): `up{service=}` 라벨은 selector app 값 아닌 Service 이름 의미.

**검증**: lint 5종 그린 + **negative test 6종**(단일 equality·regex 집합 불일치·PromQL syntax 깨짐·필수 uid 부재·scrape-absent namespace 부재·서비스 정본 누락 → 전부 exit≠0, false-green 차단) + sweep clean(escaped+service) + alert YAML/dashboard JSON 파싱. Java/gradle 입력 무변경 → build 불변(회귀 0).

**프로세스**: `/plan`(Codex 2 loop: 5→3건 수렴 — scrape-absent absent() 제약·ADR 범위 정정·PromQL syntax·coverage·sweep) → `/work`(diff 리뷰 1 loop 4건[P1:3 dashboard 변수 버그·lint 필수uid·namespace + P2:1 정본고정] 전부 반영, negative test 자체검증) → `/ship`([PR #68](https://github.com/Kimgyuilli/PeakCart/pull/68), 4 커밋). 부산물: PLAN-BLINDSPOTS **B11**(escaped-quote/형제라벨 sweep false-green).

**다음**: 🎯 **구현 ① PR3 전체 종료**(이미지/CI #66 · k8s #67 · 관측성 #68) → 구현 ② 서비스별 DB 물리 분리(order-service 전환기 migrator·cold-start initContainer 정리). 후속 비차단: D-016 full lint-digest·`flywayMigrateShared` 수복·alert delivery(L-004).

---

## 구현 ② 서비스별 DB 분리 — PR1 (교차 도메인 FK 드롭) — [#69](https://github.com/Kimgyuilli/PeakCart/pull/69)

> 구현 ①(5서비스 풀 분해) 종료 후, 5서비스가 단일 공유 DB(`mysql/peekcart`)를 쓰는 상태를 DB-per-service 로 닫는 작업의 1단계. 물리 스키마 분리(PR2) 전에 교차 도메인 FK 를 먼저 제거하는 저위험 선행 단계. (ADR-0012 §D1)

**작업**:
- **P1** `V13__drop_cross_domain_fks.sql` — 교차 도메인 FK 6개 드롭(`fk_carts_user`·`fk_cart_items_product`·`fk_orders_user`·`fk_order_items_product`·`fk_payments_order`·`fk_notifications_user`). user_id/product_id/order_id 컬럼은 유지(ID 참조 보존). 동일 스키마 내 FK(refresh_tokens/addresses→users·products→categories·inventories→products·cart_items→carts·order_items→orders)는 유지.
- **P2** 소유 경계 검증 — 5서비스 전부 자기 소유표만 `@Table` 매핑(교차 `@Table`/`@SecondaryTable` 0, nativeQuery 는 order 자기 `product_price_cache` 뿐).
- **P3** `./gradlew build test` 8모듈 BUILD SUCCESSFUL(9m46s) — FK 드롭 후 통합테스트 회귀 0.

**핵심 결정**:
- DB-per-service 에서 교차 도메인 FK 는 유지 불가(타 스키마 참조) → 물리 분리 전 선제거. 무결성은 이벤트 + 로컬 캐시(ADR-0012 D2/D3)로 이미 대체됨(strangler #56~#63 수용) → DB-level 교차 FK 는 중복 안전장치라 제거 안전.
- forward-only 단일 마이그레이션(V13, 전환기 order-service migrator 적용). 물리 스키마 분리·Flyway 모듈화는 PR2 이연.

**프로세스**: `/plan`(Codex 2 loop: 5→5건 수렴 — ADR-0012 D1 표↔코드 드리프트(stock_reservations/payment_cancellations)·전환 모드·B1b k8s 리소스명 스윕·D5 fail-fast·물리격리 판정 SQL / 거버넌스: stock_reservations 모델변경은 PR2 ADR-0016 분리) → `/work`(diff 리뷰 0건 자동통과) → `/ship`([PR #69](https://github.com/Kimgyuilli/PeakCart/pull/69), 2 커밋). consistency precheck warnings(ADR-0016 미존재 = PR2 산출물 선참조) 사유 기록 후 진행.

**다음**: PR2 — Flyway per-service 모듈화 + 물리 스키마 분리(1 인스턴스 + 5 스키마: `peekcart_<svc>`·계정 격리) + k8s mysql init/secret 분화 + CI smoke 전환 + B5/B8b 전환기 잔재 제거 + ADR-0016 신설/ADR-0012 Partially Superseded(P14). 이후 PR3 retention 스케줄러(D5).

---

## 구현 ② 서비스별 DB 분리 — PR2 (Flyway per-service + 물리 스키마 분리) — [#71](https://github.com/Kimgyuilli/PeakCart/pull/71)

> PR1(교차 FK 드롭) 후, 단일 공유 DB 를 **1 MySQL 인스턴스 + 5 스키마(`peekcart_<svc>`)** 로 물리 분리. 각 서비스가 자기 스키마·자기 계정·자기 Flyway 이력으로 자기 테이블만 소유 (ADR-0012 §D1 · ADR-0016).

**작업 (P4~P14)**:
- **P4** 5× `<svc>/src/main/resources/db/migration/V1__init_<svc>.sql` 통합 베이스라인 — 공유 V1~V13 누적 결과를 서비스별 자기 테이블 최종 형태로 분배(교차 FK 제외, 동일 스키마 FK 만 유지). order `product_price_cache` seed(`INSERT…SELECT FROM products`)는 cross-DB 라 제거(product.updated replay 로 대체).
- **P5/P6/P7** 5서비스 `flyway.enabled:true`(order B5 전환기 특권 소멸) · datasource `…/peekcart_<svc>`+계정 분화(ADR-0007) · `:common/db/migration` 소멸 + `build.gradle` flyway 플러그인/`flywayMigrateShared` 제거.
- **P8** outbox poller `aggregate-types` allowlist 제거(3 발행 서비스) — 스키마 분리로 소유권 자연 보장 → `findPendingEvents`/`countByStatus` 자기 스키마 전체로 단순화(B8b). `ProductOutboxOwnershipIntegrationTest` 재작성.
- **P9** k8s mysql init ConfigMap(`.sh` — 5 DB/계정/격리 GRANT, **비밀번호 Secret env·ConfigMap literal 금지**) + `mysql-secret` 5 pw + 5 per-svc secret DB 자격 분화 + base kustomization 등록.
- **P10/P11/P12** 전환 데이터 폐기(compose down -v) · 비-order initContainer order-readiness 게이트 제거(독립 마이그레이션) · smoke flyway 이미지 스텝 제거 + compose mysql init SQL.
- **P13** 통합테스트 cross-domain 시드 제거(notification/order×2/payment: 실제 행 시드→임의 ID 참조, FK 제거로 불요) + 공유 `cleanDatabase()` 스키마 적응형(information_schema 동적 조회·flyway_schema_history/shedlock 제외·FK_CHECKS finally 복구).
- **P14** **ADR-0016 신규**(예약=별도 `stock_reservations`·Payment=`payment_cancellations`, D1/D3 재기록) + ADR-0012 `Partially Superseded by ADR-0016` + README 인덱스 + `05-data-design`/`02-architecture` Layer1 동기화.

**핵심 결정**:
- **마이그레이션 consolidation**: 그린필드(보존 prod 이력 없음)라 13 교차 마이그레이션을 끌지 않고 서비스별 단일 `V1__init_<svc>.sql`. `ddl-auto:validate` 부팅 정합.
- **비밀번호 위치**(GW-2): init ConfigMap 엔 비밀 없는 DDL 골격, 비밀번호는 Secret env entrypoint(.sh). GRANT 는 자기 스키마 격리 + **최소권한**(DROP 미부여 — Flyway baseline 은 CREATE/ALTER/INDEX/REFERENCES+DML 로 충분).
- **거버넌스**: D1/D3 드리프트는 사실 오류가 아닌 결정 변경 → update-log 우회 금지(README:11-14) → 신규 ADR-0016 + Partially Superseded.

**검증**: `./gradlew build test` 8모듈 BUILD SUCCESSFUL(10m, 2회 — GW-2 fix 후 재검증). 물리 격리 판정 SQL·k8s rollout 검증은 PR2 머지 후(P15-k8s, Test plan 미체크).

**프로세스**: `/plan`(Codex 2 loop: PR2/PR3 재검토 5→2건 — ADR 거버넌스 전파·P9 ConfigMap 비밀번호·k8s rollout 검증·README 영향파일·Dockerfile assert·P9-sweep 처분표) → `/work`(diff split 3 chunks aggregate=ok, 4건[P1:1 GRANT DROP·P2:3 cleanDatabase 세션누수/ADR 위치/Order ERD] 전부 반영) → `/ship`([PR #71](https://github.com/Kimgyuilli/PeakCart/pull/71), 8 커밋). consistency precheck ok(ADR-0016 실재).

**다음**: PR3 — retention/cleanup 스케줄러(`processed_events`/`outbox_events`, ShedLock 잡) + floor fail-fast 가드(D5 식 = kafka-retention/consumer-downtime/dlq-replay/backfill max). L-008/011 종결. (인스턴스 물리 분리·k8s 실배포 검증은 후속.)

---

## 구현 ② 서비스별 DB 분리 — PR3 (retention/cleanup 스케줄러) — [#72](https://github.com/Kimgyuilli/PeakCart/pull/72)

> PR2(물리 스키마 분리) 후, `processed_events`(멱등성 창)·`outbox_events`(PUBLISHED)의 무한 증가를 ShedLock 배치 스케줄러로 닫는다. 보존기간이 D5 floor 미만이면 부팅 실패(fail-fast). ADR-0012 §D5 구현 → **L-008/011 종결**.

**작업 (P16~P19)**:
- **P16** `processed_events` retention 스케줄러(product/order/payment/notification). `common` 단일 typed `IdempotencyRetentionProperties` + **`@AssertTrue` cross-field**(4 `Duration` floor `max ≤ retention`) → 소유 서비스만 `@EnableConfigurationProperties` 활성(**user 누출 0** — `@ConfigurationPropertiesScan` 부재). floor 키 4종 base.
- **P17** `outbox_events` cleanup 스케줄러(product/order/payment). predicate `status='PUBLISHED' AND published_at < cutoff` — **PENDING/FAILED/`published_at IS NULL` 자연 보존**(유실 금지).
- **배치 삭제 계약**: `cutoff` 실행 시작 1회 계산 + `cleanup.batch-size`×`max-batches-per-run` 반복(unbounded DELETE 방지)·per-batch `@Transactional`(리포지토리 native `DELETE … LIMIT`). V2 마이그레이션에 삭제 기준 컬럼 인덱스(`processed_at`·`(status, published_at)`).
- **서비스×잡 매트릭스(물리 배치)**: 잡 클래스를 소유 서비스 모듈에만 둠(기존 `global/*` 복제 패턴). processed=4·outbox=3·**user=0**(구조적 부재).
- **notification 신규 ShedLock 인프라**: 소비 전용이라 미보유였음 → `shedlock-spring`/`-jdbc-template` dep + `ShedLockConfig` + `@EnableScheduling` + `shedlock` 테이블(V2).
- **P18** 테스트: floor 검증·max·fail-fast(`ApplicationContextRunner`)·base-only 배치 가드·processed/outbox 다중 batch 삭제·PENDING/FAILED/NULL/미만료 보존·**5서비스 매트릭스**(product/order/payment=both, notification=processed only, user=cleanup·retention props bean 0).
- **P19** `./gradlew build test` 8모듈 BUILD SUCCESSFUL(9m16s) + 신규 통합테스트 그린(3m6s).

**핵심 결정**:
- **floor = 교차필드 불변식**(Codex work 2회차 P1): 단순 필드 제약이 아니라 `max(4창) ≤ retention` → `@AssertTrue` cross-field. 구현 위치를 common 단일 typed properties 로 고정(5서비스 중복 정의·Duration 파싱 드리프트 차단).
- **bean 활성화 = 소유 서비스 물리 배치**(택1 확정): auto-config/`@ConditionalOnProperty` 대신 클래스 물리 부재로 매트릭스 성립(user=0 자연).
- **replay 정책**: TTL 만료 후 동일 eventId 재처리는 멱등 보장 밖 → 새 eventId/운영자 중복 확인(§2 트레이드오프).

**프로세스**: `/plan`(Codex 2 loop: PR3 재검토 5→3건 — 매트릭스/outbox FAILED 보존/kafka-retention SSOT → 2회차 floor 구현위치·대량삭제 배치·활성화 메커니즘, 전부 반영) → `/work`(diff single 리뷰 1 loop, 0 P0/0 P1/**3 P2**[5서비스 매트릭스·outbox 다중 batch·base-only 정적 가드] 자동통과분 전부 반영·검증) → `/ship`([PR #72](https://github.com/Kimgyuilli/PeakCart/pull/72), 3 커밋). consistency precheck ok.

**다음**: 구현 ③ Spring Cloud Gateway(선행 ADR-0013). 후속 비차단: 인스턴스 물리 분리(URL 교체 가역 승격)·k8s 실배포 rollout 검증·D-002 격리 재측정.

---

## 구현 ③ Spring Cloud Gateway — PR1 (RS256/JWKS dual-validation) — [#73](https://github.com/Kimgyuilli/PeakCart/pull/73)

> ADR-0013 D1/D2 의 크립토 기반. HS256 **대칭키** 공유 → RS256 **비대칭키**: User 만 개인키 서명, 모든 서비스가 공개키(kid)로 검증. Gateway 중앙 검증·header-trust 는 PR3, 본 PR 은 서비스 in-process 검증을 유지하며 RS256 을 얹는다(전환기 dual-validation).

**작업 (P1~P5)**:
- **P1** `JwtKeyProperties`(`app.jwt.rs256`) + `PemKeyLoader`(PKCS#8/SPKI PEM→RSA) + `RsaPublicKeyRegistry`(kid→공개키, JWKS 원본). common-auth `@EnableConfigurationProperties` 확장.
- **P2** `JwtTokenSigner`(User 전속) RS256 서명 + JWT 헤더 `kid`. 발급은 RS256 단일.
- **P3** `JwtTokenVerifier` dual-validation — jjwt `keyLocator` 로 헤더 `alg`/`kid` 검사: RS256 → registry kid 선택(미등록 거부), 전환기 **HS512** fallback(레거시 alg 정확 한정, 기본 off), 그 외(none/HS256/HS384) allow-list 거부.
- **P4** User JWKS endpoint `/.well-known/jwks.json`(kty/use/alg/kid/n/e, base64url 선행0 트리밍) + permitAll.
- **P5** 테스트: verifier 8종(RS256 왕복·미등록 kid·위조·kid부재·HS512 on/off·HS256 거부·none)·JWKS 스키마·서명 latency p50/p95.

**핵심 결정**:
- **fallback = HS512 정확 한정**(Codex work P1 #1): 512bit 시크릿이라 레거시 토큰은 실제 HS512(HS256 아님, plan-verify-against-code) → allow-list 과확장(`startsWith("HS")`) 방지.
- **fallback 기본 off**(Codex work P1 #2): base yml `hs256-fallback-enabled: false`(RS256 단일). 전환 배포·전환 테스트만 명시 활성화(bounded), PR4 에서 제거.
- **개인키 산출물 비포함**(Codex work P1 #3, ADR-0013 D2): 개인키를 main resources 에서 제거 → 테스트는 `:common` testFixtures(test-scope), 공개키는 `:common` main(비밀 아님, JWKS 공개). 로컬 dev=gitignored 파일 마운트, k8s CSI=PR3.
- 기존 HS 서명 통합테스트는 dual-validation 으로 재작성 없이 그린(fallback opt-in 전환창 시뮬레이션).

**검증**: `./gradlew build test` 8모듈 BUILD SUCCESSFUL(회귀 0).

**프로세스**: `/plan`(Codex 2 loop: 6→2 수렴 — JWKS 운영조건·refresh 데이터 처분·NetworkPolicy 검증·ADR immutable(S9 기존재)·B11·KMS latency → 2차 P6 근거·B5 키위치) → `/work`(PR1 diff single 리뷰 1 loop, 4건[P1×3 보안 posture·P2×1 테스트] 전량 반영·재검증) → `/ship`([PR #73](https://github.com/Kimgyuilli/PeakCart/pull/73), 3 커밋 docs/feat/test). consistency precheck ok.

**다음**: PR2 Refresh Token Reuse Detection(D4 — family_id/status 상태전이·family deny Redis) → PR3 Gateway 모듈+header-trust(D3) → PR4 관측성 S9+HS 제거(D5).

---

## 구현 ③ Spring Cloud Gateway — PR2 (Refresh Token Reuse Detection) — [#74](https://github.com/Kimgyuilli/PeakCart/pull/74)

> ADR-0013 D4. 삭제 기반 rotation 은 탈취된 refresh token 재사용을 감지 못한다. `family_id`/`status` 상태전이로 전환해 reuse 를 감지하고, 감지 시 family 전체 무효화 + Redis family deny 로 이미 발급된 access token 까지 차단한다. 전환기(Gateway PR3 이전)라 검증은 리소스 서비스 in-process 유지.

**작업 (P6~P10)**:
- **P6** `V2` 마이그레이션: 평문 `token`(+unique) 드롭 → `token_hash`(sha256, unique)·`family_id`·`status`(ACTIVE/ROTATED/REVOKED)·`grace_until`·`replaced_by_token_id`. 그린필드라 기존 row 전량 무효화. CHAR→VARCHAR(Hibernate validate 는 String 을 VARCHAR 로 기대).
- **P7** `RefreshToken` 상태전이 엔티티(+`RefreshTokenStatus`) 평문 미저장. 동시성 전이는 조건부 벌크 UPDATE(affected rows)로 원자성 — `rotateActive`/`consumeGraceOnce`/`forceRotate`/`revokeFamily`/`revokeAllByUserId`(삭제 기반 `deleteByToken` affected 판정을 상태전이로 이전).
- **P8** `AuthService.refresh` 재작성: ACTIVE 원자 로테이션(grace 부여)·ROTATED grace 1회 소비 vs 초과 reuse·REVOKED reuse. reuse 진입 3경로(grace 초과·forceRotate miss·REVOKED)를 `detectReuse`(family revoke+deny) 단일화. Redis grace(`addGracePeriod`/`consumeGracePeriod`) 제거→DB `grace_until`.
- **P9** `TokenIssuer.issue` 시그니처에 `familyId`→access token `family_id` claim. Redis `auth:deny:family:<id>` write(User)/read(전환기 common-auth `JwtFilter`+`RedisTokenBlacklistLookupAdapter`, PR3 Gateway 이관). family_id 부재 레거시 토큰은 family deny 미조회(blacklist 만).
- **P10** 단위 10종 + 통합 6종(Testcontainers MySQL/Redis): 마이그레이션 스키마·grace 원자성·동시 로테이션 1건만·grace 성공 ACTIVE 1개·forceRotate miss 결정적 family 무효화·reuse→revoke→deny.

**핵심 결정**:
- **reuse 무효화 커밋 보존**: 요청은 USR-004 거부하되 family 무효화는 커밋 필수. `REQUIRES_NEW` 는 outer refresh 트랜잭션 행 락과 **self-deadlock**(lock wait timeout) → 폐기. `RefreshTokenReuseException` + `@Transactional(noRollbackFor)` 로 무효화만 커밋, 다른 거부 경로(동시 loser INSERT·만료) 롤백 유지.
- **grace 성공 ACTIVE 1개**: 평문 미저장이라 첫 replacement 를 반환 불가 → 새 발급 + 기존 replacement force-rotate. `forceRotate != 1`(replacement 가 이미 전이됨)이면 ACTIVE 2개 위험 → 보수적 family revoke(방금 INSERT 한 새 토큰도 함께 REVOKED, noRollbackFor 커밋).
- **Redis deny 격리**: `detectReuse` 내 `denyFamily` try/catch — Redis 실패가 DB revoke 를 롤백시키지 않음. deny 미기록은 access TTL bounded, blacklist read fail-closed 로 최종 안전.
- **family_id 부재 계약**: claim 부재 ≠ Redis 조회 실패 → blacklist 만 검사(`auth:deny:family:null` 오조회·NPE·레거시 전면 401 방지).

**검증**: `:peekcart-common-auth:test :user-service:test` BUILD SUCCESSFUL(회귀 0). order-service 전체빌드 실패는 컨테이너 리소스 경합(단독 그린, D-019 계열).

**프로세스**: `/plan`(Codex 3 loop: 4→2→2 — grace 원자성/deny 키 계약/token_hash unique → 전환기 enforcement(a)안·ACTIVE 1개 불변식 → family_id 부재 계약·force-rotation 비순환, 전량 반영) → `/work`(diff single 2 loop: 3 P1[forceRotate 가드·Redis 격리·REVOKED 합류] → 1 P2[결정적 회귀테스트] 전량 반영. REQUIRES_NEW→noRollbackFor 전환은 통합테스트 self-deadlock 실측으로 확정) → `/ship`([PR #74](https://github.com/Kimgyuilli/PeakCart/pull/74), 3 커밋 feat/test/docs). consistency precheck ok.

**다음**: PR3 Gateway 모듈 + header-trust 전환(D3, family deny read 를 Gateway 로 이관) → PR4 관측성 S9 + HS512 fallback 제거(D5).

---

## 구현 ③ Spring Cloud Gateway — PR3a (Gateway shadow 배포) — [#75](https://github.com/Kimgyuilli/PeakCart/pull/75)

> ADR-0013 D3. PR3 는 단일 PR 로 실행 불가 — 롤아웃 ④(header-trust 배포)와 ⑤(Authorization 중단·verifier 삭제)를 **하나의 이미지로 구분 배포할 수 없고 역순 롤백도 불가능**하다. 계획에 **PR3a~d 실행 분할**을 신설하고 그 첫 단계를 수행. Gateway 는 검증하되 `Authorization` 을 **그대로 전달**해 구버전 서비스(`JwtFilter`)와 병행 동작한다.

**작업 (P11~P13·P16)**:
- **P11** `gateway` 모듈(WebFlux) 신설 + **라우트 정본** 확정 — placeholder 금지, 실 controller prefix 대조: auth/users→user, products/**admin**/products→product, **cart(단수)**/orders→order, payments→payment, notifications→notification. JWKS(`/api/v1` 밖)·swagger·api-docs 는 외부 라우트 미노출.
- **P12** reactive 인증 필터 3단계: 외부 `X-User-*` **항상 strip**(공개 경로 포함) → 서명/만료(RS256 via **User JWKS 정본**, 전환기 HS512) → blacklist/family deny(PR2 키 계약 그대로) → 신뢰 헤더 주입(+ 검증된 userId 를 exchange attribute 로). **응답 행렬**: 401(서명·만료·exp부재·unknown kid·deny) / 429(초과) / 503(JWKS·Redis 장애) / readiness=false(cold start usable key 0).
- **P13** route-class별 RateLimiter — 인증 후 **검증된** userId, 인증 전/공개 IP.
- **P16** Dockerfile gateway COPY + CI matrix 6 + canonical **도메인 5(ADR-0010 §5) / 인프라 1** 분리(`image-contract-lint`·`promote-images`).

**핵심 결정**:
- **gateway ≠ `:common` 소비자**: `common/build.gradle` 이 `spring-boot-starter-web`(servlet)·JPA·Kafka 를 **`api` 로 전이 노출** → 의존 시 WebFlux 런타임에 MVC 유입(Boot 가 MVC 로 부팅). 응답 DTO 는 gateway-local, 가드(`assertGatewayHasNoServletDeps`, `check` 연결)+`GatewayReactiveBootstrapTest` 로 이중 고정.
- **JWKS 는 merge 아닌 snapshot 교체**: `put` 누적이면 User 가 침해 키를 내려도 재시작 전까지 계속 수용 → 성공·비어있지 않은 응답만 통째 교체, 실패/빈 응답에만 LKG 유지.
- **fail-closed RateLimiter 자체 구현**: SCG 기본 `RedisRateLimiter` 는 Redis/Lua 오류를 삼켜 `allowed=true`(fail-**open**) → ADR-0013 D3 미충족. 고정 윈도우 카운터로 대체하고 오류 전파 → 503. `deny-empty-key` 는 빈 키만 처리라 대체 불가.
- **readiness ≠ health**: JWKS 미확보를 `HealthIndicator` DOWN 으로 내면 루트 `/actuator/health` 까지 503 → **이미지 스모크가 통과 불가**(스모크 망에 user-service 없음). liveness(프로세스 생존)/readiness(트래픽 수용) 분리, 스모크는 gateway 한정 liveness.
- **actuator 관리 포트(8081) 분리**: gateway 는 외부 진입점이라 8080 동일 포트면 `/actuator/prometheus` 가 라우트 없이도 직접 노출.
- **인증 필터 order = -100**: 라우트 필터(order 1..n)보다 **먼저** 실행돼야 RateLimiter 가 검증 전 위조 `X-User-Id` 를 키로 쓰지 않는다.

**검증**: `./gradlew clean build` BUILD SUCCESSFUL(14m6s, 가드 2종 실행 확인) · gateway 테스트 **56건 0 실패** · `docker build SERVICE=gateway` OK(539MB) · `docker-health-smoke.sh gateway:ci` passed · `image-contract-lint` matrix 6/6.

**프로세스**: `/plan`(Codex 3 loop: timeout → 13건 → 8건, 전량 반영. loop2 가 자기모순 3건 적발 — conformance 대상이 같은 PR 에서 삭제됨·family-less 계약 충돌·JWKS 기대값 404/200 불일치) → `/work`(diff 2536L>2000 → **3-chunk split 전량 리뷰**, 15건→중복제거 12건[P1 10/P2 5] 전량 반영. 필터 순서 역전·`exp` 부재 무기한 토큰·폐기 kid 잔존·Redis fail-open 이 실제 보안 결함) → `/ship`([PR #75](https://github.com/Kimgyuilli/PeakCart/pull/75), 4 커밋). consistency precheck ok.

**후속(명시)**: gateway k8s 매니페스트 부재로 `IMAGE_CONTRACT_TRANSITION=1` 재설정 — **PR3b 에서 매니페스트 추가 후 제거 필수**(full 6/6). 계정 차원 rate limit 미구현(body-caching 필요, `?email` 쿼리 성분은 회피 가능해 제거하고 IP 단독으로 축소). conformance golden vector 미구현(servlet 가드가 `:common` testFixtures 의존을 막아 리소스 파일로 재설계 필요).

**다음**: PR3b(k8s gateway + NetworkPolicy + ClusterIP 환원 + ServiceMonitor) → PR3c(header-trust 전환 + ADR-0014 D2-c exit) → PR3d(Authorization 중단 + verifier 삭제) → PR4(관측성 S9 + HS512 제거).

---

## 구현 ③ Spring Cloud Gateway — PR3b (gateway k8s 배포 표면) — [#76](https://github.com/Kimgyuilli/PeakCart/pull/76)

> ADR-0013 D3. PR3a 가 gateway 모듈을 코드로 완성했으나 k8s 매니페스트가 없어 배포 표면이 비어 있었고, 그 때문에 `IMAGE_CONTRACT_TRANSITION=1` 전환기 게이트가 CI 에 남아 있었다(5/6 SUSPENDED). 본 PR 은 gateway 를 클러스터에 올릴 수 있게 만들고 그 꼬리를 닫는다. **트래픽 전환은 하지 않는다** — 5서비스 직접 경로는 canary 롤백 경로라 살려 둔다(제거는 PR3c). **ADR 변경 없음.**

**작업 (P24~P30)**:
- **P24·P26** base `k8s/base/services/gateway/{deployment(+Service),configmap}.yml` + overlay(minikube NodePort **30080** — 구 단일앱 진입점 승계 / gke Internal LB · deployment patch×2 · `images[]` **6번째** · **gateway HPA** minReplicas 2).
- **P25** `gateway/src/main/resources/application-k8s.yml` 신설 = k8s 연결값(업스트림 5·JWKS·Redis) **단독 소유**(ADR-0007). 라우트 uri 는 리스트 요소라 프로파일 override 불가 → base placeholder 를 `${app.gateway.upstream.<svc>-uri}` **정규 계층형 키**로 교정(환경변수 표기법을 프로퍼티 이름으로 고착시키던 형태 제거). JWKS 는 스칼라라 placeholder 없이 프로파일이 직접 override.
- **P27** `IMAGE_CONTRACT_TRANSITION` 제거 → **full 6/6** + **`scripts/gateway-exposure-lint.sh` 신설**(+ CI policy step 등록, self-test 포함).
- **P28·P29** 롤아웃 runbook(계획 §7 — 배포 전 digest/revision 기록·probe 4종·canary 3단계 임계·**역순 rollback**)·gke README 외부 노출 절.
- **P30** `K8sProfileConnectionPropertiesTest` 신설(연결키 8종 origin + 라우트 9개 placeholder 연결).

**핵심 결정**:
- **ServiceMonitor 미생성(결정 가)**: `servicemonitor-selector-lint` canonical 은 도메인 5 **정확 일치**이고 ADR-0015 S5/S6.d 가 그 집합을 계약으로 고정 → gateway SM 을 넣으면 selector-lint + scrape-absent 5 equality + alert regex 5-set 이 동시에 깨져 **ADR 계약 변경** 동반. 관측성 6 확장은 PR4 에서 ADR 과 일괄. PR4 계약 선고정: `gateway-metrics` Service labels `{app: gateway, monitoring-role: metrics}` ↔ SM matchLabels 동일 두 키(공용 `app=gateway` 만 쓰면 SM 이 public Service 까지 매칭해 lint 실패).
- **Service 8080 단일·probe 8081(결정 나/다)**: overlay 가 이 Service 를 NodePort/LB 로 patch 하므로 8081 을 함께 선언하면 **LB 가 `/actuator/prometheus` 를 게시** — PR3a 의 포트 분리가 k8s 층에서 무효화된다. probe 는 `management.server.port` 를 따라 8081(도메인 5서비스는 8080이라 복사 시 함정).
- **Secret 미생성(결정 라)**: 소비 비밀 0(RS256 개인키=user-service 전용·HS512 off). 빈 Secret 대신 lint 가 **이름이 아니라 참조**로 부재 강제.
- **gateway HPA = 단일 HPA 원칙의 명시적 예외(결정 마)**: 도메인 확장 정책이 아니라 인프라 가용성 — 단일 진입점 replica 1 은 인증 경로 전체 SPOF.
- **`observability-promql-lint` 정정**: S6.d "SM 이 매칭하는 Service" 를 `deployment.yml` **전체 glob** 으로 근사하고 있어 SM 없는 인프라 Service 추가 시 오탐 → **계약이 아니라 구현 근사의 문제**라 ADR 변경 없이 SM matchLabels 기반으로 수정.
- **렌더 성공 ≠ 계약 검증**: `port: 8080/targetPort: 8081`·Job/CronJob/직접 Pod(hostPort 8081)는 `kubectl kustomize` 를 전부 통과 → 전용 lint 필요. self-test 는 조작 입력 **13종이 의도한 검사에** 걸리는지 **진단 문자열까지 대조**(non-zero 여부만 보면 다른 위반에 걸려도 통과).

**검증**: CI policy lint **7종 전부 그린**(namespace·image-contract **full 6/6**·gateway-exposure·self-test 13/13·servicemonitor **5 유지**·observability-ssot·observability-promql) · `kubectl kustomize` 양 overlay 렌더(양성+음성 3종) · `./gradlew build` BUILD SUCCESSFUL(gateway **66건 0 실패**·가드 5종) · `docker build SERVICE=gateway` + `docker-health-smoke.sh gateway:ci` passed · 신규 테스트 음성 확인(프로파일 키 오타 → BUILD FAILED).

**프로세스**: `/plan`(Codex **3 loop**: timeout→8건→7건→4건, 전량 반영. GP-1 에서 SM/관측성 확장을 PR4 로 이연 결정. loop2 가 내 lint 스펙의 실제 구멍[targetPort]을, loop3 이 우회 경로[CronJob/hostPort]와 내가 만든 문서 SSOT 붕괴를 적발 — attempts 4/3 은 사용자 승인으로 상한 초과) → `/work`(코드 723줄 single 리뷰, **7건[P1:3/P2:4] 전량 반영**. **CI red 를 리뷰어가 발견** — 로컬에서 lint 4종만 돌리고 관측성 2종을 건너뛴 누락) → `/ship`([PR #76](https://github.com/Kimgyuilli/PeakCart/pull/76), 6 커밋). consistency precheck ok.

**미확보(명시)**: 실 클러스터 canary 증적 — runbook 은 작성했으나 미실행. 상시 클러스터 부재로 실 probe(보호/공개/spoof·오류율)는 **PR3c 의 GKE 보안 smoke 세션에서 NetworkPolicy 음성·양성과 함께 1회** 수행한다. **렌더 성공을 canary 통과로 기록하지 않음** → PR3c 진입 조건("100% 전환 유지")은 본 PR 머지로 충족되지 않는다.

**다음**: PR3c(5서비스 ClusterIP 환원 + NetworkPolicy + header-trust 전환 + ADR-0014 D2-c exit + **GKE 보안 smoke = canary 증적 합류**) → PR3d(Authorization 중단·verifier 삭제) → PR4(관측성 S9 + `gateway-metrics`/SM + lint 6 + HS512 제거).

---

## 구현 ③ Spring Cloud Gateway — PR3c (header-trust 전환 + ClusterIP 환원 + NetworkPolicy) — [#77](https://github.com/Kimgyuilli/PeakCart/pull/77)

> ADR-0013 D3. PR3a/PR3b 로 Gateway 를 클러스터에 올린 뒤, 리소스 서비스가 JWT 를 재검증하는 대신 Gateway 가 검증 후 주입한 `X-User-*` 신뢰 헤더로 인증하도록 전환한다. 동시에 5서비스 직접 노출을 제거(ClusterIP)하고 NetworkPolicy 로 gateway 경유를 강제해 header-trust 의 spoofing 을 봉쇄한다. **servlet verifier 삭제(ADR-0014 D2-c exit)·Authorization 전달 중단은 PR3d** — PR3c 는 구 컴포넌트를 잔존시켜 rollback 안전창을 남긴다.

**작업 (P31~P38)**:
- **P31** common-auth `HeaderAuthenticationFilter`(servlet `OncePerRequestFilter`) + `HeaderTrustSecurityConfigurer`. **3-state 계약**: 세 헤더 부재→anonymous 통과 / `X-User-Id`(양의 정수)·`X-User-Role`(USER/ADMIN) 각 1개(+familyId 전환기 선택)→인증 / 형식오류(부분·blank·중복·비숫자·미허용 role)→401(entrypoint, 500·anonymous fallback 아님). `LoginUser(userId,role,familyId)` + resolver(authority→role, details→familyId) + testFixtures(role/familyId).
- **P32** 5 SecurityConfig `jwtSecurityConfigurer`→`headerTrustSecurityConfigurer` 스왑(공통 정책·`@EnableMethodSecurity`·PUBLIC_URLS 불변). `AuthController.logout`→`authService.logout(userId, familyId)` = family deny(non-null 시) + `revokeAllByUserId`(`jwtTokenVerifier` 의존 제거·고아 import/field 정리).
- **P33** 테스트: `HeaderAuthenticationFilterTest` 3-state 13종 + `UserSecurityIntegrationTest`·`NotificationSecurityIntegrationTest` Bearer→`X-User-*` 재작성(configurer·MdcFilter parity·actuator permitAll) + `AuthServiceTest` logout family/family-less.
- **P34** overlay service patch 10개(minikube NodePort 5·gke Internal LB 5) 삭제 → base ClusterIP 환원 + 5 base Deployment `strategy.rollingUpdate.maxUnavailable:0`.
- **P35** `k8s/base/networkpolicy.yml` — **ingress-only**·`podSelector{component:backend}`(5서비스 선택·gateway `component:gateway` 자동 제외)·ingress ①gateway `{app:gateway}` ②monitoring NS Prometheus scrape 예외(둘 다 TCP 8080). `networkpolicy-contract-lint.sh`(고정 5 Deployment 이름 식별·peer+포트 결합, self-test 8종).
- **P36** PR3c 무중단 rollout runbook(계획 §8 — 안전 순서: NetworkPolicy/ClusterIP 선행→header-trust rollout, 역순 rollback).
- **P37** `gke-security-smoke.sh`(`--barrier`: enforcement hard-fail[Dataplane V2 OR networkPolicy.enabled]·non-gateway 차단·gateway 공개 200·Prometheus up·직접경로 도달불가 / default: barrier+canary+증적).
- **P38** 검증: 전체 빌드 9모듈 BUILD SUCCESSFUL · CI lint 7종 그린(image-contract full 6/6·servicemonitor 5 유지·gateway-exposure 13/13·**networkpolicy-contract 8/8**) · 렌더 양성(5 ClusterIP·gateway NodePort/LB).

**핵심 결정**:
- **configurer 스왑 = 필터만 교체, 공통 정책 전부 보존**(Codex diff #6): csrf/STATELESS/entryPoint/accessDeniedHandler/MdcFilter 순서 유지 — 누락 시 401/403·MDC traceId 계약이 조용히 달라진다.
- **NetworkPolicy 는 podSelector 기반**(Codex diff #3/#4): vanilla NP 는 peer 를 ServiceAccount 로 선택할 수 없다 → SA 기반 허용 폐기·Gateway SA PR3c 미도입. `component:backend` 로 5서비스만 선택.
- **안전 순서**: NetworkPolicy/ClusterIP 를 header-trust rollout 보다 먼저(구 이미지 Bearer 검증이라 위조 `X-User-*` 무효). gateway 의 Authorization 전달은 PR3c 내내 유지(rollback 안전창).
- **검증 도구 false-green 차단**(Codex diff #1/#2/#5): lint 는 보호 대상을 고정 Deployment 이름으로 식별해 라벨 드리프트를 잡고 peer 를 포트와 결합 검사. smoke barrier 는 kubectl-run 실패↔curl 결과 분리(`000000` 버그·kubectl 실패 오판 제거)·직접경로는 HTTP 응답 오면 실패. self-test 로 각 시나리오 재현·차단.

**프로세스**: `/plan`(Codex 3 loop: timeout→10건→4건 전량 반영. NetworkPolicy SA 미지원·barrier 실행화·configurer parity 등) → `/work`(diff single 리뷰 1 loop, **6건[P1:4/P2:2] 전량 반영** — 내 검증 도구 자체의 false-green 이 핵심. np-lint self-test 8/8 이 Codex 지적 시나리오 재현·차단) → `/ship`([PR #77](https://github.com/Kimgyuilli/PeakCart/pull/77), 4 커밋 feat(auth)/test(auth)/feat(k8s)/docs). consistency precheck ok.

**미확보(명시)**: **GKE 실 클러스터 보안 smoke 증적** — `gke-security-smoke.sh` 는 작성 완료했으나 실 클러스터 barrier(enforcement·직접경로 차단·spoof)·canary 실행은 라이브 클러스터 단계라 본 PR 미포함. **렌더/lint 성공을 canary 통과로 기록하지 않는다**(계획 §6/P38). PR3c 완료 필수 게이트이며 **PR3d 진입 전 확보 필수**.

**다음**: PR3d(**재정의 — 아래 ADR-0017 채택 참조**) → PR4(관측성 S9 + `gateway-metrics`/SM + lint 6 + HS512 제거). **선행: GKE 보안 smoke 증적 확보.**

---

## 구현 ③ Spring Cloud Gateway — PR3d 재정의 (ADR-0017 채택: Gateway 서명 내부 토큰) — 2026-07-25

> **결정만 기록(구현 전).** PR3c 리뷰에서 "평문 header-trust 는 NetworkPolicy 단일 통제(single control)라 근본적 방어가 아니다"를 짚고, defense-in-depth 로 격상하기로 **ADR-0017(Accepted, 경로 A)** 신설. PR3d 의 정의가 "평문 header-trust 굳히기 + verifier 삭제" → "Gateway 서명 내부 토큰(`X-Internal-Auth`) 격상"으로 바뀐다.

**핵심**:
- **평문 → 서명 assertion**: Gateway 가 `X-User-*`(평문) 대신 자기 개인키로 서명한 짧은 수명 내부 JWT(`X-Internal-Auth`, TTL≤30s)를 주입. 리소스 서비스는 Gateway 공개키로 **서명·iss(`peekcart-gateway`)·kid·exp 핀 검증** 후 claims 에서 신원 추출. 평문 신뢰 헤더 폐기 → **스푸핑 표면 0**.
- **신뢰 경계 이중화**: 신원 위조에 NetworkPolicy(네트워크) AND 서명키(암호) 동시 돌파 필요. NetworkPolicy 단독 실패(CNI 미enforce·라벨 드리프트·파드 컴프로마이즈)로 위조 불가.
- **verifier 용도 변경(삭제 아님)**: `RsaPublicKeyRegistry`/`PemKeyLoader`/`JwtKeyProperties`(공개키)는 내부 토큰 검증기로 재활용(기존 크립토 ~80% 재사용). 삭제 대상은 사용자 토큰 검증 필터(`JwtFilter`/`JwtTokenVerifier`)·서비스 측 blacklist lookup(deny 는 Gateway 소유). ADR-0014 D2-c exit 은 여전히 성립.
- **경로 A**: PR3c 가 GKE 증적 미확보 = 평문 header-trust 미배포 → 평문을 실 클러스터에 굳히지 않고 서명 assertion 을 header-trust rollout 으로 직행(dual-accept 경유). GKE 보안 smoke 는 서명 상태에서 1회 수행하며 위조 `X-Internal-Auth`·평문 직접주입 차단을 barrier 에 추가.
- **기각 대안**: 평문 유지(단일 통제) / HMAC 공유비밀(서비스 1개 컴프로마이즈=위조, blast radius) / mTLS(메시 인프라 과대, Phase 5+) / 원본 JWT 재검증(중복·지연 회귀).

**산출물**: ADR-0017(Accepted)·`docs/plans/task-impl3-pr3d-internal-token.md`(P1~P10 정본 — loop3 에서 P9/P10 추가, 상위 문서 P1~P8 표기는 2026-08-08 정정)·상위 계획 PR3d 행·P14 처분표 대체 표기.

**다음**: 새 브랜치에서 초안 `/plan`(Codex 리뷰 루프) → PR3d `/work`+`/ship`. **선행 게이트 불변: GKE 보안 smoke 증적(위조 서명 차단 포함).**

---

## 구현 ③ Spring Cloud Gateway — PR3c GKE 보안 smoke 게이트 실행 (증적 확보) — 2026-08-08 — [#79](https://github.com/Kimgyuilli/PeakCart/pull/79)

> **코드 변경 없음 — 실행/증적 세션.** PR3c([#77](https://github.com/Kimgyuilli/PeakCart/pull/77))가 "렌더/lint 성공을 canary 통과로 기록하지 않는다"며 미확보로 남긴 실 클러스터 barrier 를 1회 수행했다. PR3d 진입 조건이던 선행 게이트가 해제된다.

**환경**: 신규 GCP 프로젝트 `peekcart-gate`(조직 하위) · GKE `peekcart-loadtest`(asia-northeast3-a, e2-standard-4×3, **Dataplane V2**) · loadgen VM `peekcart-loadgen`(e2-small, 동일 VPC) · 이미지 GHCR→AR 승격 6개 digest 고정(D-016/L-016a) · kube-prometheus-stack. 세션 종료 후 `loadtest/cleanup.sh` + orphan PD 4건 수동 삭제 → **잔여 리소스 0**.

**결과**: `gke-security-smoke.sh` **barrier 5/5 + canary 3/3 전부 통과**.

| 검사 | 결과 |
|---|---|
| (1) enforcement | `datapathProvider=ADVANCED_DATAPATH` |
| (2) non-gateway Pod → order-service | 차단(`000`) |
| (3) gateway 공개 경로 | `200` |
| (4) Prometheus scrape | `up=5`(monitoring 예외 동작) |
| (5) 직접 경로 5개 | 전부 도달불가 |
| canary | 공개 `200` / 보호 무토큰 `401` / spoof `X-User-*` `401` |

**핵심 결정**:
- **검사(5) 는 그대로 돌리면 vacuous-green**: 새 클러스터엔 직접 경로가 처음부터 없어 아무 IP 5개나 넣어도 전부 `000` 이고, 모든 LB 가 **Internal** 이라 VPC 밖에서 실행하면 무엇을 넣든 `000` 이다(스크립트의 개수 검증만으로는 의미가 확보되지 않는다). → **3상태 측정으로 양성 대조군을 만든다**: ①LB有·NP無=**200**(검사가 도달을 감지함) → ②LB有·NP有=`000`(NP 단독 효과) → ③ClusterIP·NP有=`000`(표면 제거). ①→③ 직행이면 LB 삭제와 NP 적용이 동시에 일어나 무엇이 `000` 을 만들었는지 분리되지 않아 ②를 끼웠다.
- **직접 경로는 PR3c(3ed4fb4)가 삭제한 service patch 를 git 에서 복원해 실제로 띄운 주소**(합성 NodePort 아님) — 그래야 "표면 제거"가 임시 오브젝트가 아닌 계약 변경에 대한 증명이 된다. 복원 overlay 는 `k8s/overlays/gke-probe-state1/`(operator-local, `.git/info/exclude`).
- **smoke 실행 위치 = VPC 내부 VM**: gateway·5서비스 LB 가 전부 Internal 이라 노트북 실행은 검사(3) 이 `000` 으로 실패하고 검사(5) 는 무조건 통과한다.

**증적**: `docs/progress/evidence/pr3c-gke-smoke-20260808-1445.md`(스크립트 출력 + 3상태 addendum + 배포 편차).

**배포 편차(증적에 명시)**:
1. 조직 정책으로 기본 컴퓨트 SA 자동 IAM 부여가 꺼져 AR pull 403 → `roles/artifactregistry.reader`, loadgen VM 용 `roles/container.developer` 수동 부여.
2. **user-service RS256 개인키를 k8s Secret 으로 마운트**(ADR-0013 D2 의 Secret Manager+CSI 아님) — 게이트 검증 대상과 무관한 부팅 전제로 판단. **PR3d P5 CSI 계약은 본 증적으로 미충족**.
3. `SLACK_WEBHOOK_URL`·`TOSS_SECRET_KEY`·`TOSS_WEBHOOK_SECRET` 은 `docker-health-smoke.sh` placeholder 런타임 주입(실 자격증명 아님, 외부 연동 미검증).

**발견된 결함(PR3d 흡수)**: `gke-security-smoke.sh` 증적 헤더 `- canary:` 가 항상 `n/a` — `CANARY_RESULT` 가 `tee` 파이프라인 서브셸에서 설정돼 부모 셸로 전파되지 않는다. 실제 값은 로그 블록에 보존되어 본 증적은 온전. PR3d P10 이 같은 스크립트를 확장하므로 그때 수정한다.

**다음**: **PR3d 착수 가능**(선행 게이트 해제). `docs/plans/task-impl3-pr3d-internal-token.md` P1~P10 → `/work`. PR3d P10 ②(signed-only crypto barrier)도 위조 401 을 주장하려면 **정상 서명 200 양성 대조군**이 같은 이유로 필요하다.

---

## 구현 ③ Spring Cloud Gateway — PR3d-a: Gateway 서명 내부 토큰 (코드) — 2026-08-12 — [#80](https://github.com/Kimgyuilli/PeakCart/pull/80)

> PR3d 를 **PR3d-a(코드) / PR3d-b(키배포·클러스터)** 로 분할 확정하고(계획서 §9), 그중 a 를 구현했다. 평문 `X-User-*` 신뢰가 사라지고 Gateway 가 서명한 `X-Internal-Auth` 만 인증 근거가 된다(ADR-0017).

**분할 기준 = "클러스터 없이 그린이 되는가"**. 단일 PR 로 두면 코드가 CSI 인프라 준비를 기다리고, 반대로 lint 를 먼저 짜면 검사 대상 매니페스트가 없어 vacuous-green 이 된다. PR3a(이미지·코드)→PR3b(k8s 표면)와 같은 축이다.

**착수 전 코드 검증이 계획 전제 3건을 뒤집었다** (§9.1):
- **`JwtFilter` 는 PR3c 이후 이미 미배선**(5서비스 전부 `HeaderTrustSecurityConfigurer`) → 계획이 P0급으로 다룬 loop2 #1("④~⑤ 구간 verifier 활성 시 Bearer 로 Gateway 우회") 위험이 성립하지 않는다. P6 삭제는 순수 dead-code 제거 → 롤아웃 비결합.
- 같은 이유로 loop3 #3(rollback 전용 호환 이미지) **전제 소멸** — 되돌릴 대상은 직전 릴리스(PR3c) 이미지다. §7 rollback 행렬은 b 착수 시 재작성.
- **user-service 개인키의 k8s 매니페스트가 아예 없다**(GKE 세션의 ad-hoc Secret 이 유일) → 이 상태로 P7 key-ownership lint 를 만들면 검사 대상이 없어 **vacuous-green**. b 의 P5 에 user 키 CSI 정본화를 포함시켰다.

**핵심 결정**:
- **이름 계약 단일 출처**: gateway(WebFlux)와 common-auth(servlet)는 서로 의존 불가(B6)라 양쪽 리터럴이 곧 drift 원천 → 프레임워크 의존 0 인 `internal-token-contract` 모듈에 issuer/claim/헤더 이름을 한 번만 정의. 루트 가드에 allowlist 예외 1건을 열되 **계약 모듈 자신의 project/Spring 의존을 금지하는 (a2) 검사**를 함께 추가해 예외가 우회로가 되지 않게 했다.
- **키 도메인 분리(D3)**: Gateway 공개키를 `app.jwt.rs256.public-keys` 에 넣지 않는다 — `JwkController` 가 레지스트리를 통째로 JWKS 게시하므로 내부 신뢰 앵커가 노출된다. 강제는 **kid 가 아니라 SPKI DER SHA-256 fingerprint** 로 한다(같은 키를 다른 kid 로 넣는 우회 차단). lint + 5서비스 통합테스트 이중.
- **교차모듈 conformance**: 두 모듈을 잇는 단일 테스트가 불가능 → 공유 fixture 에 **커밋된 계약 토큰**을 두고 발행측/검증측이 각각 고정. 한쪽만 바뀌면 반대편이 깨진다.
- **fail-fast/fail-closed**: 키 로딩 실패·빈 키셋·범위 위반은 부팅 거부. family-less 는 발행 거부(401). `InternalTokenModeInvariant` 가 부팅 시 필터 구성을 검사(사용자 verifier 부활·체인 0개 차단).

**프로세스**: `/work`(diff 6천 줄 → **split 3 chunk 리뷰**, 24건). **분할 아티팩트 10건 기각** — chunk 를 나눠 독립 리뷰하니 다른 chunk 의 파일을 "패치에 없어 컴파일 불가(P0)"로 판정했다. full build 그린 + 파일별 chunk 소속 대조로 반증. **실제 결함 7건 전량 반영**, 그중 3건이 내가 만든 검증 도구 자체의 false-green:
1. 직접경로 Bearer 거부 테스트가 깨진 문자열 → verifier 가 부활해도 통과하는 **vacuous-negative** → 유효 access token 발급 + 4서비스에 검증키를 **일부러 등록**해 회귀 시 실제로 깨지게 함
2. 부팅 불변식이 SecurityFilterChain 0개일 때 조기 return → **fail-open** → 위반 처리
3. 키 도메인 검사가 fixture 키 1개만 대조 → 두 레지스트리 **fingerprint 집합 서로소** 검사로 교체

나머지 4건: lint 서비스 단위 검사(ITKO-006), 양성 대조군을 구체 상태로 고정(405/200/404 — "401 아님"은 403·5xx 도 그린), RS384/RS512 거부·경계값(±1s)·키 회전 overlap 테스트, 서명 지연 baseline.

**검증**: 10모듈 그린 · **575 테스트 0 실패** · 가드 5종 · lint self-test 7/7 · 서명 p95 RSA-2048 **1.80ms** / RSA-3072 **3.00ms**(예산 10/25ms, 측정 전 확정).

**미충족(명시)**:
1. **k8s gateway 매니페스트는 배포 불가** — 개인키 CSI 마운트(P5) 부재로 fail-fast 기동 거부. CI 는 apply 하지 않고 클러스터도 0이라 실효 비용 0. PR3a→PR3b 전환기와 동일 취급 — **렌더 그린을 배포 가능으로 기록하지 않는다.**
2. **부하 하 event-loop lag 미측정** — 마이크로벤치로는 불가. PR3d-b 부하 세션 이연(계획서 §9.2 명시). 초과 시 P2 (b) bounded scheduler + 포화 503.
3. ~~**Layer 1 동기화(02 / 04 §10-2) 이연**~~ → **별도 docs PR 로 해소**(아래 항목).

**다음**: **PR3d-b**(P5 CSI 키배포[user 키 정본화 포함]·P7 나머지·P8 회전 runbook·P10 GKE 2단 barrier·§7 롤아웃) → PR4(관측성 S9). **진입 조건: GKE 재기동 + Secrets Store CSI Driver 설치** — 비용상 PR4 와 같은 클러스터 세션으로 묶기를 권장.

---

## 구현 ③ Spring Cloud Gateway — PR3d-a 후속: Layer 1 동기화 (docs) — 2026-08-12 — [#81](https://github.com/Kimgyuilli/PeakCart/pull/81)

> PR3d-a 의 미충족 #3(Layer 1 동기화 이연)을 해소했다. 클러스터 비의존이라 PR3d-b 를 기다릴 이유가 없어 별도 docs PR 로 떼어냈다.

**변경**:
- **`04-design-deep-dive.md` §10-2** — "Gateway 가 평문 헤더로 전달 / 내부 서비스는 헤더 값을 신뢰" 기술을 폐기하고 서명 assertion 으로 교체. 외부 `X-User-*`·`Authorization` strip → Gateway 개인키 서명 `X-Internal-Auth` 단일 주입(사용자 Authorization 미전달) → 서비스는 서명·iss·kid·exp/iat·수명상한 검증 후 인증 주체 확립. 보안 전제를 "NetworkPolicy 가 신뢰 경계를 보장"에서 **defense-in-depth(NetworkPolicy AND 서명)** 으로 정정 — NP 우회만으로는 인증을 통과할 수 없다. **키 도메인 분리** 절 신설(Gateway 공개키는 User JWKS 에 미투입, 강제는 kid 가 아닌 SPKI DER SHA-256 fingerprint).
- **`02-architecture.md`** — §4-4 모듈 목록에 `internal-token-contract` 추가 + `peekcart-common-auth` 역할을 "전환기 JWT 검증"→"내부 토큰 검증"(ADR-0014 D2-c 종료)으로 정정 · Phase 4 다이어그램 Gateway 노드에 내부 토큰 발행 명시 · §12 Phase 4 트리의 `api-gateway/`(가상 이름·`JwtAuthFilter`)를 실제 `gateway/` 모듈 구조로 교체하고 `peekcart-common-auth`/`internal-token-contract` 추가 · k8s `services/api-gateway/`→`gateway/` · Phase 1→4 전환표 "인증 처리" 행 정정.

**남긴 것**: `00-lagacy.md` 의 동일 문구 3곳은 아카이브라 손대지 않았다. 02 §12 트리의 여타 누락(`peekcart-common-observability`, NetworkPolicy 매니페스트 등)은 ADR-0017 표면이 아니라 범위 밖.

**다음**: 변동 없음 — **PR3d-b**(GKE 재기동 + Secrets Store CSI Driver 설치 선행) → PR4(관측성 S9).

---

## 구현 ③ Spring Cloud Gateway — PR3d-b 분할 확정 (계획) — 2026-08-12 — [#82](https://github.com/Kimgyuilli/PeakCart/pull/82)

> §9.3 의 PR3d-b 를 **b-1(코드·매니페스트) / b-2(클러스터 증적)** 로 재분할했다. 분할 축은 §9 와 동일 — "클러스터 없이 그린이 되는가". 계획서 §10 신설, 코드 변경 없음.

**착수 전 grep 검증 6건 — 5건 확인, 1건 뒤집힘** (§9.1 이 전제 3건을 뒤집은 전례에 따라 b 착수 전에도 동일 검증):

- **V6 (뒤집힘) §7 은 라이브 무중단 전환이 아니다** — 클러스터 잔여 0. 구 gateway 이미지도 트래픽도 없으므로 ②~④ 는 마이그레이션이 아니라 **fresh deploy 리허설**.
  - **결정: 단계 전부 리허설 실행**. 생략하면 전환 절차·수렴 판정식(§8 loop3 #1)·rollback 행렬이 한 번도 실행 안 된 문서로 남고 `DUAL_ACCEPT` 가 미실증이라 §7 ⑥ 삭제 근거가 약해진다. P8 회전 overlap 이 같은 "선배포 → 수렴 → 전환" 메커니즘을 재사용하므로 비용도 회수된다. 증적에는 **리허설임을 명시**한다("무중단 전환 실증"이 아니라 "절차 재현"). 실트래픽 하 전환은 미검증.
- **V1** `k8s/` 에 CSI·SPC **0건**, `secretKeyRef` 는 mysql 뿐 → gateway·user 개인키 매니페스트 **둘 다 부재**. **매니페스트가 lint 보다 먼저**여야 P7 key-ownership lint 가 vacuous-green 을 피한다(PR3d-a 에서 3번 밟은 함정).
- **V2** `Mode.DUAL_ACCEPT` 는 `InternalTokenAuthenticationFilter:62` 에 실제 구현됨 → §7 ② 가능. baked 기본값이 `SIGNED_ONLY` 라 ② 는 ConfigMap override 왕복(이미지 재빌드 아님).
- **V3** `gke-security-smoke.sh:170` `{ ... } | tee` 서브셸 → `CANARY_RESULT` 미전파 재현(PR3c 흡수분, b-1 수정).
- **V4** `gateway-exposure-lint.sh:211-225` 가 "승인 CSI 정확히 1개" 교체 지점. `initContainers 0개` 계약과 충돌 없음.
- **V5** `local-keys/` gitignored, 커밋된 `.pem` 은 testFixtures 4 + 공개키 2.

**파생 정정**: §8 loop3 #3 의 "verifier + 내부토큰 필터 공존 rollback 전용 호환 이미지"는 §9.1(verifier 삭제 완료)로 **전제 소멸** — 되돌릴 대상은 직전 릴리스 이미지. 행렬은 b-1 에서 재작성.

**다음**: **PR3d-b-1** 착수(진입 조건 없음) → **b-2**(GKE 재기동 + CSI Driver + Secret Manager 키 등록) → PR4(관측성 S9).

---

## 구현 ③ Spring Cloud Gateway — PR3d-b-1: 키 배포 매니페스트 · 소유 경계 lint · 롤아웃 게이트 — 2026-08-13 — [#83](https://github.com/Kimgyuilli/PeakCart/pull/83)

> 계획서 §10.2. PR3d-a 가 남긴 "개인키가 클러스터에 도달할 경로 없음"(미충족 #1)과 PR3c 의 "user 개인키 ad-hoc k8s Secret" 편차를 **클러스터 없이 그린이 되는 범위**로 해소했다. 실 키 주입·롤아웃 실행·barrier 증적은 b-2.

**순서 제약을 먼저 지켰다** — 매니페스트가 lint 보다 먼저다(§10.1 V1). 반대로 하면 검사 대상이 없어 P7 lint 가 vacuous-green 이 된다. PR3d-a 에서 세 번 밟은 함정이라 착수 순서 자체를 계획에 못박고 시작했다.

**핵심 결정**:
- **개인키는 etcd 를 경유하지 않는다** — k8s Secret 은 base64 일 뿐이고 `kubectl get secret` 권한이면 원문이 나온다. 내부 토큰 개인키는 NetworkPolicy 우회 시 마지막 방어선이라 노출 = 전 서비스 위조다. Secret Manager → CSI → 노드 tmpfs 직접 투영(ADR-0013 D2·ADR-0017 D2). `secretObjects`·`nodePublishSecretRef` 는 이 이점을 되돌리므로 lint 가 금지한다.
- **공개키는 ConfigMap** — 비밀이 아닌데도 이미지 베이크를 안 쓴 이유는 **회전**이다. overlap 은 리스트가 1→2→1 로 변하는 일이라 이미지에 고정하면 회전이 빌드 파이프라인에 묶여 "선배포 → 수렴 → 전환"(§11) 순서를 지킬 수 없다. 인덱스 env 로 ConfigMap 편집 + 재시작만으로 끝낸다.
- **두 lint 의 검사 대상이 반대다** — exposure-lint 는 gateway **한 워크로드의 노출 표면**을, workload-key-ownership-lint 는 **"누가 그 키를 가져갔나"** 를 본다. 후자를 신설한 이유가 이것이다(같은 스크립트에 넣으면 전제[kubectl]와 실패 원인이 섞인다).

**설계 가정 1건이 테스트로 뒤집혔다**: 착수 시 Spring 이 리스트를 **인덱스 단위 병합**한다고 보고 "이미지 기본값 항상 1개" 규약을 세우려 했으나 `InternalTokenPropertiesBindingTest` 가 반증 — 리스트는 **최고 우선순위 소스가 통째로 대체**한다. 실제 동작이 더 안전하다(ConfigMap 이 신뢰 kid 집합의 단일 출처 → 회전 ⑤가 실제 폐기로 이어진다). 렌더·lint 로는 안 잡히고 b-2 실 클러스터에서야 드러났을 지점이라 §11.2 도 정정했다.

**프로세스**: `/work` single 리뷰(1,569줄). **분할 아티팩트 0건** — PR3d-a 가 3 chunk 로 나눠 24건 중 10건이 "다른 chunk 파일이 없어 컴파일 불가" 류 오판이었던 것과 대비된다. **11건 전량 실제 결함, 전량 반영**. 그중:
1. **내 검증도구 false-green 3건** — SPC 를 **이름으로만** 승인해 `resourceName` 만 상대 키로 바꾸는 우회가 열려 있었고(이 PR 의 존재 이유가 키 도메인 분리인데), 소유자를 이름으로만 판정해 `Job/gateway` 가 통과했고, 개인키 탐지가 이름 정규식이라 `bundle.pem` 에 PKCS#8 담으면 미탐이었다.
2. **내 논증 오류 1건** — "Pod 200 = 서명 주입" 은 다운스트림이 SIGNED_ONLY 일 때만 참인데 §7 ③ 구간은 DUAL_ACCEPT 다. 평문만 주입해도 200 이라 "평문 주입 0" 게이트가 false-green 이었다 → 전제를 관측으로 확인 후 아니면 거부.
3. **내 판단 오류 1건** — P10 ② "직접경로 Bearer 거부"를 "클러스터 밖에서 불가능"이라 빼고 사유를 주석에 적었는데, **gateway Pod 안에서는 가능**하다(NetworkPolicy 가 허용한 유일 peer).
4. self-test 판정을 `grep -qF`(ID 존재) → **ID×기대 횟수 multiset**(변이 35→49종). `APP_INTERNALTOKEN_MODE` ConfigMap 배치는 ADR-0007 위반(동작 규약의 프로파일 유출)이라 제거.

**추가 발견**: `rollout-convergence-gate.sh` 의 python 블록이 `\"` 이스케이프 때문에 **파싱조차 안 되는 상태**였다. 클러스터가 없어 실행될 일이 없었던 탓에 Codex 도 놓쳤고, b-2 첫 실행에서 터졌을 코드다.

**검증**: lint 10종 · **self-test 69종**(exposure 25·workload 24·np 8·itko 7·dockerfile 5) · `kustomize build` 3종 · 10모듈 빌드+테스트 · 임베디드 python 4블록 compile + 양/음성.

**미충족(명시)**:
1. **실 클러스터 미적용** — Secret Manager 에 키가 없다. 렌더 그린을 배포 가능으로 기록하지 않는다(PR3a→PR3b 와 동일 취급).
2. **barrier ② 는 gateway 경유 경로 중심** — 서비스 측 서명 검증 자체는 통합테스트 소관. 단 gateway Pod 내부 직접경로 Bearer 거부는 검증한다.
3. **minikube 도 CSI Driver 없이는 gateway 부팅 불가**(base 에 SPC 포함). PR3d-a 이전에도 개인키가 없어 마찬가지였으므로 회귀는 아니다.
4. 부하 하 event-loop lag 미측정(PR3d-a 승계).

**다음**: **PR3d-b-2** — 진입 조건 GKE 재기동 + Secrets Store CSI Driver 설치 + Secret Manager 키 등록. PR4(관측성 S9)와 같은 클러스터 세션 권장.

# PeekCart — Task 관리

> 현행 작업을 **PR 단위**로 추적한다. PR 1개 = 부채/기능 1묶음.
> 상태: `🔲 대기` / `🔄 진행 중` / `✅ 완료` / `⏸ 보류`

## 문서 맵

| 대상 | 경로 |
|---|---|
| Phase 1~3 task 이력 (아카이브) | `docs/progress/TASKS-archive-phase1-3.md` |
| Phase 4 설계·실행 로드맵 (ADR 시퀀싱·구현 순서) | `docs/progress/phase4-design-roadmap.md` |
| 진입 전 부채 해소 로드맵 (버킷 1 완결, 버킷 2/3 이관·게이트) | `docs/progress/phase4-prep-debt-roadmap.md` |
| 부채 후보 분류·승격 매핑 (L-001~L-022) | `docs/progress/phase4-prep-debt-roadmap.md §2~5` |
| Phase별 작업 이력 | `docs/progress/PHASE1.md` · `PHASE2.md` · `PHASE3.md` |

---

## 현재 단계: Phase 4 — MSA 분리

> 서비스 경계 정본 = §5(5개 풀 분해) 확정. **초기 설계 ADR(A1~A4) 완료 (#44~#47) → 구현 단계.** 구현 ① PR2 착수 중 전환기 인증 보정 **ADR-0014(A4.5)** 추가(`peekcart-common-auth`, ADR-0011 부분 무효화). 상세: `docs/progress/phase4-design-roadmap.md`.
> 착수 시 상태를 `🔄`, 머지 시 `✅` 로 갱신하고 PR/ADR 링크를 단다. 구현 각 항목의 세부 PR 분할은 해당 항목 `/plan` 착수 시 정의한다.

### 설계 (A1~A4 ✅ 완료 · A4.5 보정 ADR)

| 순서 | ADR | 작업 | 편입 부채 | 상태 |
|---|---|---|---|---|
| A1 | ADR-0010 | 서비스 분해 — §5 비준 + §4-5 정정, 5개 서비스 계약 명문화 (+ F1/F2/F3 정합) | — | ✅ [#44](https://github.com/Kimgyuilli/PeakCart/pull/44) |
| A2 | ADR-0011 | 멀티모듈 구조 (`common`+관측성+5서비스, 의존 규칙·빌드/테스트/이미지 계약) | L-016a, D-016 | ✅ [#45](https://github.com/Kimgyuilli/PeakCart/pull/45) |
| A3 | ADR-0012 | DB-per-service + 이벤트/Saga 계약 (재고 예약·`stock.reservation.result`·retention) | L-008/011, L-020-2 | ✅ [#46](https://github.com/Kimgyuilli/PeakCart/pull/46) |
| A4 | ADR-0013 | Gateway 보안 (RS256·Gateway 검증·Rate Limit·Reuse Detection·S9 관측성) | 보안 묶음 L-001/002/003/019 | ✅ [#47](https://github.com/Kimgyuilli/PeakCart/pull/47) |
| A4.5 | ADR-0014 | 전환기 인증 검증 공유 모듈 `peekcart-common-auth` (게이트웨이 이전, ADR-0011 부분 무효화) — 구현 ① PR2 중 발견 | — | ✅ [#50](https://github.com/Kimgyuilli/PeakCart/pull/50) |

### 구현 (ADR 선행 후 PR 단위) ← 현재 focus

> **구현 ① ✅ 완료 (선행 ADR-0011/ADR-0014, [#48~#68])** — PR1 스켈레톤+common → PR2 서비스 분리(5 peel·root 소멸) → PR3 Dockerfile/CI(#66)·k8s(#67)·관측성(#68). 계획서: `docs/plans/task-impl1-gradle-multimodule.md`.
> **구현 ② ✅ 완료 (선행 ADR-0012 D1/D5·ADR-0016, [#69·#71·#72])** — PR1 교차 FK 드롭 → PR2 물리 스키마 분리(1 인스턴스+5 스키마) → PR3 retention/cleanup 스케줄러(D5·L-008/011 종결). 계획서: `docs/plans/task-impl2-db-per-service.md`. **다음 focus = 구현 ③(Spring Cloud Gateway, 선행 ADR-0013).** (인스턴스 물리 분리는 URL 교체로 가역 승격 — 후속.)
> **⚠️ peel 순서 정정 (2026-06-15)**: Product 가 Order 의 동기 빈(`ProductPort`)에 묶여 ① 단독 peel 불가(부팅 실패) — independent 한 **User 를 PR2b 로 먼저** 떼고, Order/Product/Payment 는 ②(DB)/④(Saga)/⑤(캐시)를 교차한 사가 클러스터로 함께 분리한다(ADR-0010 F2·ADR-0012 D3, 새 ADR 불필요). 상세: `phase4-design-roadmap.md §2`.

| 순서 | 작업 | 선행 ADR | 편입 부채 | 상태 |
|---|---|---|---|---|
| ① | Gradle 멀티모듈 전환 (PR1 ✅ [#48](https://github.com/Kimgyuilli/PeakCart/pull/48) · PR2a-1 ✅ [#51](https://github.com/Kimgyuilli/PeakCart/pull/51) common-auth 추출+JWT verify/sign 분리 · PR2a-2a ✅ [#52](https://github.com/Kimgyuilli/PeakCart/pull/52) SlackPort→:common+ADR-0011 §D2 정정 · PR2a-2b ✅ [#53](https://github.com/Kimgyuilli/PeakCart/pull/53) notification-service peel(첫 서비스 분리)+ActuatorSecurityConfig(S4)+공유스키마→:common · PR2b ✅ [#55](https://github.com/Kimgyuilli/PeakCart/pull/55) user-service peel(발급 owner·blacklist token-hash dual-read·SlackNotificationClient @ConditionalOnProperty) · **사가 클러스터 strangler-1 ✅ [#56](https://github.com/Kimgyuilli/PeakCart/pull/56) 재고 예약/복구 이벤트화(예약 원장 상태머신·all-or-nothing·CAS 복구, ADR-0010 F2·ADR-0012 D3)** · **strangler-2 ✅ [#57](https://github.com/Kimgyuilli/PeakCart/pull/57) 단가 로컬 캐시 CQRS(product.updated 발행·@Version 순서키·원자 upsert stale-skip·getUnitPrice 동기 seam 제거, ADR-0012 ⑤·L-006)** · **strangler-3 ✅ [#58](https://github.com/Kimgyuilli/PeakCart/pull/58) 2-phase 예약 확정(CONFIRMED)/보상 + 결제 게이트(ORD-008·paymentRequestedAt 타임아웃)(ADR-0012 D3/④)** · **strangler-4 ✅ [#61](https://github.com/Kimgyuilli/PeakCart/pull/61) `verifyProductExists` 로컬 캐시화(ProductPort 동기 seam 제거·캐시 미스 ORD-009·order↔product src 결합 금지 가드)(ADR-0010 F2·ADR-0012 ⑤) → Order↔Product production 동기 결합 0** · **Product peel ✅ [#62](https://github.com/Kimgyuilli/PeakCart/pull/62) product-service 모듈 분리(첫 *발행* 서비스: outbox/idempotency/ShedLock 복제·공유 DB poller 소유권 분리 aggregateType allowlist·ProductSecurityConfig)+root 테스트 디커플(ADR-0010/0011/0012/0014, DB 물리분리는 ② 이연)** · **strangler-5 Order↔Payment 동기 결합 제거 ✅ [#63](https://github.com/Kimgyuilli/PeakCart/pull/63) `OrderPort` seam 제거(verifyOrderOwner→payment-로컬 userId·transitionToPaymentRequested→`payment.requested` 이벤트)+reserve→pay/취소 게이트 payment-로컬 복원·@Version·이벤트 역전 영속 marker(ADR-0012 §D4 refine)+가드 `assertNoOrderPaymentSourceCoupling` → Order↔Payment src 동기 결합 0** · **Order peel PR-a ✅ [#64](https://github.com/Kimgyuilli/PeakCart/pull/64) order-service 모듈 분리(order 도메인+16테스트 이관·outbox/idempotency/ShedLock 복제·OrderKafkaConfig producer-owns NewTopic(order.*)·OrderSecurityConfig·data-redis 무조건(ADR-0014 blacklist)·root poller PAYMENT 좁힘·root 통합테스트 payment-observable 디커플·OrderApplicationTests 부팅 스모크, ADR-0010/0011/0012/0014)** · **Payment peel + root 해체 PR-b ✅ [#65](https://github.com/Kimgyuilli/PeekCart/pull/65) payment-service 모듈 분리(마지막 도메인)+root app 해체(global/app/src 삭제·bootJar→aggregator)+order-service Flyway migrator 승계(B5)+product-service NewTopic 소유+global 테스트 rehome(:common 유닛·payment-service 통합)+root 단일 이미지 제거(PR3 이연), ADR-0010/0011/0012/0014) → 5개 서비스 풀 분해 완료(root app 소멸)** · **PR3a ✅ [#66](https://github.com/Kimgyuilli/PeakCart/pull/66) 서비스별 Dockerfile(단일+ARG SERVICE·멀티모듈 COPY·base digest 고정 L-016a)+CI 이미지 매트릭스(images build/smoke·publish main push job 분리·save/load·digest 강제)+image-contract-lint per-service(canonical 5·images/publish matrix 일치·전환기 SUSPENDED 게이트)+smoke 공유스키마 선행 마이그레이션(flyway 이미지). 후속부채: flywayMigrateShared 깨짐(Docker flyway 정본 우회)** · **PR3b ✅ [#67](https://github.com/Kimgyuilli/PeakCart/pull/67) k8s base/overlays per-service 재구성(5서비스 deployment/cm/secret/servicemonitor·비-order initContainer order-service readiness gate·minikube/gke overlay patch×10·gke images[]5·order-service 단일 HPA)+Slack real↔no-op 게이팅(SlackFallbackConfig·notification fail-fast/product·order·payment no-op·presence-based+placeholder 함정 제거)+자격증명 fail-fast(SLACK/TOSS committed secret 제거·smoke 런타임 주입)+mysql-secret 분리(infra→secret dangling)+servicemonitor count==5·image-contract full 5/5·promote-images(D-016)(ADR-0004/0005/0006/0007/0010/0014). 후속: PR3c 관측성+ADR-0015·full lint-digest** · **PR3c ✅ [#68](https://github.com/Kimgyuilli/PeakCart/pull/68) 관측성 per-service 재설계(grafana alert 8 rule by-clause+regex·scrape-absent 5 equality·dashboard $application custom 변수·observability lint 2종 per-service 재작성+CI 재활성+promtool syntax+sweep 가드)+ADR-0015 신규·ADR-0009 Partially Superseded(ADR-0006/0009/0010/0015). negative test 6종 false-green 차단** → **구현 ① PR3 전체 종료(이미지/CI #66·k8s #67·관측성 #68)**) | A2·A4.5 | L-016a, D-016 | ✅ |
| ② | 서비스별 DB 분리 (PR1 ✅ [#69](https://github.com/Kimgyuilli/PeakCart/pull/69) 교차 도메인 FK 6개 드롭(`fk_carts_user`·`fk_cart_items_product`·`fk_orders_user`·`fk_order_items_product`·`fk_payments_order`·`fk_notifications_user`, 컬럼 유지·ID 참조 대체)·소유 경계 검증(5서비스 자기 테이블만 매핑)·8모듈 그린, ADR-0012 D1 — 물리 분리 선행 저위험 단계 · 계획서 `task-impl2-db-per-service.md` PR1/PR2/PR3 분할) · **PR2 ✅ [#71](https://github.com/Kimgyuilli/PeakCart/pull/71)** Flyway per-service 통합 베이스라인(5× `V1__init_<svc>.sql`·`:common/db/migration` 소멸·flywayMigrateShared 제거)+물리 스키마 분리(1 인스턴스+5 스키마 `peekcart_<svc>`+계정/격리 GRANT)+datasource per-schema(ADR-0007)+outbox allowlist 제거(스키마 분리로 자연 소유권·B8b)+k8s mysql init ConfigMap(.sh·비밀번호 Secret env·literal 금지)·per-svc secret 분화·initContainer 게이트 제거+compose/smoke mysql init 전환+통합테스트 cross-domain 시드 제거·cleanDatabase 스키마 적응형+**ADR-0016 신규**·ADR-0012 Partially Superseded·Layer1 동기화(8모듈 그린, GRANT 최소권한 DROP 미부여) · **PR3 ✅ [#72](https://github.com/Kimgyuilli/PeakCart/pull/72)** retention/cleanup 스케줄러(processed_events·outbox_events, ADR-0012 D5) — floor cross-field fail-fast(`IdempotencyRetentionProperties` @AssertTrue max(4창)≤retention·소유 서비스만 @EnableConfigurationProperties→user 누출 0)+서비스×잡 매트릭스(processed=product/order/payment/notification·outbox=product/order/payment·user=0, 물리 배치)+배치 삭제 계약(cutoff 1회·batch-size×max-batches-per-run·per-batch @Transactional·V2 삭제기준 인덱스)+outbox predicate `PUBLISHED AND published_at<cutoff`(PENDING/FAILED/NULL 보존)+notification 신규 ShedLock 인프라(dep·ShedLockConfig·@EnableScheduling·shedlock 테이블)+정책키 base-only(ADR-0007)+5서비스 매트릭스 통합테스트(8모듈 그린) → **L-008/011 종결** | A3 | L-008/011 | ✅ |
| ③ | Spring Cloud Gateway (PR1 ✅ [#73](https://github.com/Kimgyuilli/PeakCart/pull/73) RS256/JWKS dual-validation — `JwtKeyProperties`/`PemKeyLoader`/`RsaPublicKeyRegistry`·verifier alg allow-list(kid 선택·HS512 fallback bounded off)·User RS256 서명(kid)+JWKS `/.well-known/jwks.json`·개인키 산출물 비포함(testFixtures/파일마운트)·latency 측정, ADR-0013 D1/D2) · **PR2 ✅ [#74](https://github.com/Kimgyuilli/PeakCart/pull/74) Refresh Token Reuse Detection** — 삭제 기반→`family_id`/`status`(ACTIVE/ROTATED/REVOKED) 상태전이(V2·`token_hash` sha256·평문 미저장)+원자 조건부 UPDATE(`rotateActive`/`consumeGraceOnce`/`forceRotate`/`revokeFamily`, Redis GETDEL grace 대체)+`AuthService.refresh` 재작성(grace 1회·reuse 진입 3경로 `detectReuse` 단일화)+reuse 무효화 커밋 보존(`RefreshTokenReuseException`+`@Transactional(noRollbackFor)`, REQUIRES_NEW self-deadlock 폐기)+`family_id` claim(`TokenIssuer`/`TokenClaims`)+Redis `auth:deny:family:<id>` write/read+전환기 common-auth `JwtFilter` enforcement(family_id 부재 레거시 blacklist-only·Redis deny 실패 격리)(ADR-0013 D4) · **PR3a ✅ [#75](https://github.com/Kimgyuilli/PeakCart/pull/75) Gateway shadow 배포** — `gateway` 모듈 신설(WebFlux·`:common` 미의존 — common 이 servlet starter-web 을 `api` 전이 노출해 MVC 부팅 유발, 가드 `assertGatewayHasNoServletDeps`+REACTIVE 부팅 테스트 이중 고정)+라우트 정본 확정(`cart` 단수·`admin/products` 선순위·JWKS/swagger 외부 미노출)+reactive 인증 필터(외부 `X-User-*` 항상 strip→RS256(JWKS)/전환기 HS512 검증→blacklist·family deny→신뢰 헤더 주입, **응답 계약 401/429/503 분리**)+JWKS **snapshot 통째 교체**(폐기 kid 즉시 무효화·실패 시에만 LKG)+**fail-closed RateLimiter 자체 구현**(SCG 기본은 Redis 오류를 `allowed=true` 로 삼켜 fail-OPEN → ADR-0013 D3 위반)+readiness/liveness 분리(cold start 트래픽 미수신)+actuator 관리 포트 8081 분리+Dockerfile/CI matrix 6·canonical **도메인 5+인프라 1** 분리(gateway k8s 매니페스트는 PR3b → `IMAGE_CONTRACT_TRANSITION=1` 전환기 게이트 재설정) · **PR3b ✅ [#76](https://github.com/Kimgyuilli/PeakCart/pull/76) gateway k8s 배포 표면** — base 매니페스트(Deployment+Service+ConfigMap·`k8s/base/services/gateway/`)+overlay(minikube NodePort **30080** 승계·gke Internal LB·patch×4·`images[]` 6)+**gateway HPA**(minReplicas 2 — order-service 단일 HPA 원칙의 명시적 예외, SPOF 근거 ADR-0013 D3)+`application-k8s.yml` 신설(k8s 연결값 단독 소유·라우트 placeholder `${app.gateway.upstream.<svc>-uri}` 정규화, ADR-0007)+**`IMAGE_CONTRACT_TRANSITION` 제거 → image-contract-lint full 6/6**(PR3a 꼬리 종결)+**`gateway-exposure-lint` 신설**(렌더가 통과시키는 노출 위반 전담 — Service 8080 단일·`targetPort` 고정·Job/CronJob/Pod 포함 PodSpec 전수·Secret 참조 전무·hostNetwork/hostPort·configMap 배선·`maxUnavailable=0`, self-test **13종 진단 대조**)+**`observability-promql-lint` 정정**(S6.d 기대집합을 전체 Service glob→**SM 매칭 Service** 기준으로, ADR 변경 없음)+롤아웃 runbook(계획 §7)**결정: ServiceMonitor·Secret 미생성**(SM 은 ADR-0015 S5/S6.d 계약 변경 동반이라 PR4 이연 / gateway 소비 비밀 0). **canary 실증적 미확보 — PR3c GKE 보안 smoke 세션 합류** · **PR3c ✅ [#77](https://github.com/Kimgyuilli/PeakCart/pull/77) header-trust 전환 + 5서비스 ClusterIP 환원 + NetworkPolicy** — `HeaderAuthenticationFilter`(3-state: 부재→anonymous/정상→인증/형식오류→401 entrypoint)+`HeaderTrustSecurityConfigurer`(JwtSecurityConfigurer 공통 정책 전부 보존·필터만 교체, 구 servlet verifier 잔존)+5 SecurityConfig 전환+`LoginUser(userId,role,familyId)`·`logout(userId,familyId)`(family deny+revokeAllByUserId, family-less=deny 생략)+overlay service patch 10개 삭제→ClusterIP 환원·base `maxUnavailable:0`+**NetworkPolicy**(ingress-only·podSelector `component:backend`·gateway peer+monitoring scrape 예외, SA 미도입 — vanilla NP 는 SA peer 미지원)+**`networkpolicy-contract-lint`**(고정 5 Deployment 이름 식별·peer TCP8080 결합, self-test 8종)+**`gke-security-smoke`**(barrier enforcement hard-fail[Dataplane V2 OR networkPolicy.enabled]+non-gateway 차단+직접경로 도달불가+canary). **Codex diff 리뷰 6건(P1×4 barrier/lint false-green+P2×2) 전량 반영**. **✅ GKE 실 클러스터 smoke 증적 확보(2026-08-08, [#79](https://github.com/Kimgyuilli/PeakCart/pull/79))** — `gke-security-smoke.sh` barrier 5/5(enforcement `ADVANCED_DATAPATH`·non-gateway 차단·공개 200·scrape up=5·직접경로 5개 도달불가)+canary 3/3(공개 200/보호 401/spoof 401). **검사(5) 는 3상태 양성 대조군으로 vacuous-green 제거**(LB有·NP無=200 → LB有·NP有=000 → ClusterIP·NP有=000: NP 단독 효과와 표면 제거를 분리). 증적 `docs/progress/evidence/pr3c-gke-smoke-20260808-1445.md`. **잔여 편차**: user-service 개인키를 ADR-0013 D2 의 Secret Manager+CSI 가 아닌 k8s Secret 으로 마운트 → **PR3d P5 CSI 계약은 본 증적으로 미충족** · **PR3d 재정의(ADR-0017 Accepted, 경로 A)**: 평문 header-trust → **Gateway 서명 내부 토큰(`X-Internal-Auth`) 격상**(defense-in-depth — NetworkPolicy AND 서명) + Authorization 전달 중단 + 서비스 측 사용자 토큰 verifier·blacklist 삭제(ADR-0014 D2-c exit, `RsaPublicKeyRegistry`/`PemKeyLoader` 는 삭제 아니라 내부 토큰 검증기로 **용도 변경**). 정본 `docs/plans/task-impl3-pr3d-internal-token.md`(P1~P10). **§9 에서 PR3d-a(코드) / PR3d-b(키배포·클러스터)로 분할 확정** — 분할 기준 = "클러스터 없이 그린이 되는가", 근거 §9.1 코드 검증 3건(JwtFilter 는 PR3c 이후 이미 미배선 → loop2 #1 위험 소멸·rollback 행렬 전제 소멸 / user-service 개인키 k8s 매니페스트 부재 → P7 key-ownership lint 가 vacuous-green 이라 b 가 user 키도 정본화). · **PR3d-a ✅ [#80](https://github.com/Kimgyuilli/PeakCart/pull/80)** 평문 header-trust 폐기 → Gateway 서명 내부 토큰(P1·P2·P3·P4·P6·P9+P7 property-ownership): `internal-token-contract` 신설(10모듈, 프레임워크 의존 0 — 발행 gateway/검증 common-auth 가 서로 의존 불가라 이름 계약 단일 출처, 루트 가드 allowlist 1건+계약모듈 무오염 (a2) 검사)·`InternalTokenIssuer`(개인키 부팅 fail-fast·family-less 발행거부 401)+`GatewayAuthenticationFilter` 재작성(평문 3종→서명 토큰 단일 주입·strip 확대·**Authorization 전달 중단**)·`InternalTokenVerifier`+**전용** `InternalGatewayPublicKeyRegistry`(alg RS256 정확 핀·iss/kid/exp/iat·수명상한·skew·claim 타입 전부 검증, **키 도메인 분리** — User JWKS 레지스트리에 미투입)·`InternalTokenAuthenticationFilter`(3-state 보존·SIGNED_ONLY 는 평문 X-User-* **무시**)+`InternalTokenModeInvariant`(부팅 시 필터 구성 검사)·P6 삭제 8종(`JwtFilter`/`JwtTokenVerifier`/`JwtSecurityConfigurer`/blacklist lookup 2/`TokenClaims`/`TokenParseException`/`HeaderAuthenticationFilter`, **ADR-0014 D2-c exit**)·`internal-key-ownership-lint`(SPKI DER SHA-256 fingerprint — kid 대조 우회 차단, self-test 7종)+CI 배선·575 테스트 0 실패·가드 5종 그린. **Codex diff 리뷰 3 chunk 24건 → 분할 아티팩트 10건 기각·실제 결함 7건 전량 반영**(내 검증도구 false-green 3건 포함: 직접경로 Bearer 가 깨진 문자열이라 vacuous-negative → 유효 토큰+검증키 등록 / 부팅 불변식 체인 0개 fail-open / 키도메인 검사가 fixture 키 1개만 대조 → 집합 서로소). **미충족 명시**: k8s gateway 매니페스트는 CSI 키 부재로 배포 불가(PR3a→PR3b 동일 취급)·부하 하 event-loop lag 미측정(마이크로벤치 baseline 만 확정 RSA2048 p95 1.80ms/3072 3.00ms)·Layer1 동기화는 후속 docs PR 로 분리(→ ✅ [#81](https://github.com/Kimgyuilli/PeakCart/pull/81) 해소) / **PR3d-b 는 [#82](https://github.com/Kimgyuilli/PeakCart/pull/82) 에서 b-1/b-2 로 재분할**(계획서 §10, 착수 전 grep 검증 6건 — V6 뒤집힘: 클러스터 잔여 0 이라 §7 ②~④ 는 라이브 마이그레이션이 아니라 fresh deploy **리허설**, 단계 전부 실행하되 증적에 리허설 명시 / V1: CSI·개인키 매니페스트 0 → **매니페스트가 lint 보다 먼저**여야 P7 vacuous-green 회피 / loop3 #3 rollback 호환 이미지 전제 소멸 → 행렬 재작성): **b-1 ✅ [#83](https://github.com/Kimgyuilli/PeakCart/pull/83)** P5 매니페스트(gateway/user `SecretProviderClass`+CSI read-only 마운트[**user 키 ad-hoc Secret→CSI 정본화**, PR3c 편차 해소]·`internal-token-keys`/`internal-token-binding` ConfigMap 신설[공개키 PEM + 인덱스 env — 회전 overlap 을 이미지 재빌드 없이]·5서비스 배선·`application-k8s.yml` 키 경로)+P7 lint(`gateway-exposure-lint` "비밀 0"→**"승인 CSI 정확히 1개"**[SPC1↔volume1↔mount1·양쪽 readOnly·`secretObjects`/`nodePublishSecretRef`[SPC+**inline** 양쪽] 금지·resource/alias/mountPath/namespace exact]·**`workload-key-ownership-lint` 신설**[Deployment/StatefulSet/DaemonSet/ReplicaSet/Job/CronJob/Pod 전수+init/ephemeral·소유자 **(ns,kind,name)**·SPC **내용** allow-list·개인키 base64 decode PEM marker 탐지·서비스별 배선+JWT 도메인 env 오염])+P8 회전 runbook(§11)+P10 barrier ② 신설(위조·평문·**정상 서명 양성 대조군**·**gateway Pod 내 직접경로 Bearer 거부**)+`CANARY_RESULT` 서브셸 버그 수정+§7 수렴 판정식 스크립트화(`rollout-convergence-gate.sh`)+§12 rollback 행렬 재작성. **`InternalTokenPropertiesBindingTest` 가 설계 가정을 반증** — Spring 리스트는 인덱스 병합이 아니라 **최고 우선순위 소스가 통째 대체**(ConfigMap 이 신뢰 kid 집합 단일 출처 → 회전 ⑤가 실제 폐기). **Codex 리뷰 11건 전량 반영**(분할 아티팩트 0 — single 모드, 내 검증도구 false-green 3건 + 내 논증 오류 1건[§7 ③ 은 DUAL_ACCEPT 라 "Pod 200=서명 주입" 불성립] 포함). lint 10종·**self-test 69종**·렌더 3종·10모듈 그린 · **b-2 🔲 대기**(실 키 주입·§7 ①~⑥ 리허설·P10 barrier ①② 증적·P8 overlap 1회·부하 하 event-loop lag — 진입 조건: GKE 재기동+Secrets Store CSI Driver 설치+Secret Manager 키 등록, PR4 와 동일 세션 권장) / PR4 관측성 S9 대기 | A4 | 보안 묶음 | 🔄 |
| ④ | Choreography Saga (착수 전 코드 검증에서 **범위 재확정** — ADR-0012 ④ 산출물 4개 중 2개는 strangler-1~5 로 이미 완료, 실질 잔여는 "이미 도는 saga 의 미결 종료 상태" 닫기. 계획서 `task-impl4-choreography-saga.md` P1~P15, PR ④-a/b/c/d 분할 · **④-a ✅ [#84](https://github.com/Kimgyuilli/PeakCart/pull/84)** L-013 실측(결정적 재현 — `@Version` 부재 시 취소 선커밋→`PAYMENT_COMPLETED`·결제 선커밋→`CANCELLED` 양방향 lost update, 전이 가드는 스냅샷 기준이라 무력) → `Order @Version` 승격 + 충돌 정책(타임아웃 취소=재시도 포기 / 결제완료가 취소 주문에 도착=`ORD-003` DLQ 대신 보상)+**예약 lease 계약**(Product 부여 → `stock.reservation.result` 공유 → Order 선취소 · Payment 만료 승인 거부 `PAY-010` · sweeper 는 만료+유예 후 회수, 고정 TTL 은 살아있는 주문 재고를 뺏어 oversell)+**"예약 확정·결제 미시작 PENDING" 수명 상한 부재 해소**(기존 2개 만료 조회가 못 비우는 구간, 갭 존재 증명 테스트 동반)+**`PAID_BUT_CANCELLED` 영속 보상 원장**(V4 — Slack 은 order-service 에서 no-op 이고 `processed_events` 커밋으로 재소비 불가라 알림은 종결 근거가 못 됨)+legacy backfill 3종·인덱스·배치 상한. **Codex 리뷰 5건(P0 1) 전량 반영** — P0 가 "oversell 을 닫았다"는 초안 주장을 반증(승인 검사 후 **같은 트랜잭션**에서 PG 호출, 그 시점 주문은 아직 `PENDING`) → 승인 마진으로 창만 축소하고 **fence 미구현을 계획 §2.6 R-1 로 명시**(진짜 fence 는 saga 프로토콜 변경 → ADR 선행 별도 PR). 604 테스트·lint 10종 그린 · **④-b/c/d 🔲 대기**, ④-c 는 R-2 배포 의존성(원장→환불) 있음) | A3 | L-013 (실측 후 승격) | 🔄 |
| ⑤ | CQRS 로컬 캐시 | A3 | L-006 (L-005 선결 완료) | 🔲 |
| ⑥ | Cursor 페이지네이션 | — | — | 🔲 |
| — | D-002 격리 재측정 | — | D-002 (분리 후) | 🔄 추적 |

---

## 진입 전 부채 해소 (버킷 1) — ✅ 완료 (아카이브)

> 9개 PR 전부 완료(Tier A · D-012~D-015 · D-017~D-019, PR #37~#43). 상세 PR 테이블·시퀀스는 `docs/progress/phase4-prep-debt-roadmap.md §2`(SSOT)에 보존.

---

## 개발 부채 (Tech Debt)

> 해결 완료(D-001~D-012) 상세는 아카이브(`TASKS-archive-phase1-3.md §개발 부채`) 보존. 여기서는 **live + 신규**만 추적.

### Live / 신규

| ID | 영역 | 요약 | 묶음 | 상태 |
|---|---|---|---|---|
| D-002 | Performance | 캐시 TPS ×2.31(목표 ×3 미달). 1차 병목 CPU 확증, 2차 후보(MySQL 풀 / Redis 락 contention) 미분리 | Phase 4 Order Service 분리 후 격리 재측정 | 🔄 추적 |
| D-012 | CI / Deliverable | CI 가 품질 게이트가 아니다 — PR Docker build·smoke 부재, branch protection 미설정, NS 누출 lint 부재 | 버킷 1 (L-014/015/017) | ✅ 완료 |
| D-013 | Resilience | 발행 경로 resilience 갭 — DLQ 발행 미확정(유실), outbox `.get()` 타임아웃 부재와 polling cycle 상한 미정의(워커 잠식) | 버킷 1 (L-010/012) | ✅ 완료 |
| D-014 | Observability | 선결 측정 표면 부재 — 캐시 적중률 / outbox 파이프라인 메트릭 | 버킷 1 선택 (L-005/009) | ✅ 완료 |
| D-015 | Deploy/CI | CI push image repo(`peekcart`) ↔ K8s base/GKE 참조(`peakcart`) 계약 불일치 → GHCR→AR 복사·base 배포 실패 가능 | 버킷 1 (도식검토) | ✅ 완료 |
| D-017 | Observability | Grafana alert rule 존재하나 Slack contact point/provisioning 부재 → "alert→Slack" 경로 미완성. 범위 정정(②)으로 봉합, delivery 는 L-004 이관 | 버킷 1 (도식검토) | ✅ 완료 |
| D-018 | Docs | `loadtest/reports/2026-04-29/REPORT.md` Redis PVC 1Gi ↔ 현 매니페스트 512Mi 드리프트 | 버킷 1 (도식검토) | ✅ 완료 |
| D-020 | Resilience / Payment | **결제 승인 ↔ 로컬 커밋 불일치** — `PaymentCommandService` 가 DB 트랜잭션 안에서 Toss 승인을 호출하고 그 뒤 상태·Outbox 를 저장한다. 승인 성공 후 커밋이 실패하면 **외부 과금은 남고 로컬은 롤백**된다. `WebhookService` 는 웹훅을 로그로만 적재하고 상태를 복구하지 않는다. 필요한 것은 승인 idempotency key + 웹훅/조회 기반 reconciliation + 영속 상태머신 (구현 ④ GW-1 리뷰 #3 에서 발견, choreography saga 가 아닌 **PG reconciliation** 표면이라 ④ 범위 밖) | Phase 4 후속 (외부 PG 장애 주입 환경 필요) | 🔲 |
| D-019 | Testing | `OutboxKafkaIntegrationTest.orderCancelled_e2e` CI 간헐 실패 → **(a) 타이밍 flake 확정**(프로덕션 회귀 아님). D-013 producer 타임아웃 타이트화로 콜드 스타트 첫 발행이 실패하면 단발 poll 테스트는 재폴링이 없어 PENDING 고착 → `await` 타임아웃. 프로덕션은 스케줄러 재발행으로 자가치유. 테스트만 `pollUntilPublished` 로 수정(하드닝 유지) | 버킷 1 마무리분 (D-013 여파) | ✅ 완료 |

### 해결 완료 (아카이브 참조)

D-001(✅), D-005(✅), D-006(✅), D-007(✅), D-008(✅), D-009(✅), D-010(✅), D-011(✅), D-012(✅) · D-003(Won't Fix) · D-004(운영지식) — 상세: `docs/progress/TASKS-archive-phase1-3.md §개발 부채`.

---

## Phase 4 — MSA 분리 (예정)

> 로드맵 §3(버킷 2) 이관 부채를 각 Phase 4 task 에 편입. 착수 시 D- 승격 또는 task 항목 흡수.

| 순서 | 작업 | 편입 부채 |
|---|---|---|
| 1 | Gradle 멀티모듈 전환 | L-016(a) digest 고정, L-020(2) consumer group 라벨, D-002 격리 재측정 |
| 2 | 서비스별 DB 분리 | L-008/L-011 retention(보존기간=멱등성 창 상한 결정) |
| 3 | Spring Cloud Gateway | **보안 묶음** L-001/L-002/L-003/L-019 (RS256 전환 + KMS/Vault + Reuse Detection + 인증 관측성) |
| 4 | Choreography Saga | — |
| 5 | CQRS 로컬 캐시 | L-006 Redis fallback (L-005 선결) |
| 6 | Cursor 페이지네이션 | — |
| — | 운영 관측성 | L-004 Slack 채널 재설계 |

상세: `docs/07-roadmap-portfolio.md §16` · 로드맵 §3.

---

## 보류 (측정 후 결정)

> 게이트: **17편 후속 부하 세션** 실측. 나오면 모놀리스 단계 선제 승격, 아니면 Phase 4 분리 시 자연해소(L-007)/필수화(L-013).

| ID | 영역 | 측정 게이트 |
|---|---|---|
| L-007 | 주문 *생성* 경로 "락 ⊃ 트랜잭션" 불변식 + 재고 차감 retry 정책 미정 | 동일-상품 경합 시 재고 차감 `PRD-004`/`OptimisticLockingFailureException` 응답률 유의 |
| ~~L-013~~ | ~~주문 *상태 전이* 동시성(`Order @Version` 부재)~~ | **✅ 해소 (2026-08-13, [#84](https://github.com/Kimgyuilli/PeakCart/pull/84))** — 게이트대로 실측 선행. `payment.completed` 소비 ↔ 타임아웃 취소를 두 EntityManager 가 같은 스냅샷을 읽도록 강제한 **결정적** 재현으로 양방향 lost update 확증(취소 선커밋→`PAYMENT_COMPLETED`, 결제 선커밋→`CANCELLED`) → `@Version` 승격 + 충돌 정책 확정. 상세: 계획서 §5.1 |

상세·승격 시 동반 결정: 로드맵 §4.

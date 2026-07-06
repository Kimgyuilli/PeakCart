# task-impl3-spring-cloud-gateway — 구현 ③ Spring Cloud Gateway (RS256 + Reuse Detection + S9)

> 선행 ADR-0013(Accepted). 보안 묶음 L-001/002/003/019 편입. Phase 4 구현 로드맵 ③.
> PR 분할: PR1 RS256/JWKS(dual-validation) → PR2 Refresh Reuse Detection → PR3 Gateway 모듈 + header-trust 전환 → PR4 관측성 S9 + HS256 잔재 제거.
> 각 PR 은 자체 `/work` + `/ship` 을 거치며, 해당 PR 착수 시 세부 계획을 보강한다(impl② PR3 선례).

## 1. 목표

- **RS256 비대칭키 전환**(D1): User 만 개인키 서명, Gateway 가 공개키 1차 검증, 리소스 서비스 미재검증(헤더 신뢰). JWKS 공개키 배포.
- **Refresh Token Reuse Detection**(D4): 삭제 기반 rotation → `family_id`/`status` 상태전이. reuse(탈취) 감지 시 family 전체 무효화 + Redis family/session deny(access token 즉시 차단).
- **Spring Cloud Gateway**(D3): 5서비스 path 라우팅, JWT 검증 3단계(서명→blacklist/deny fail-closed→신뢰 헤더 주입), Redis RateLimiter(route-class별).
- **관측성 S9**(D5): 인증실패/reuse/429 메트릭(counter+사유 태그), ADR-0009 S9 행 추가.
- **성공 기준**: 8+1 모듈 그린, gateway 통해서만 인증 통과(리소스 서비스 direct ingress 거부), reuse 재제시→family revoke 확증, RS256 왕복·HS256 위조 거부.

## 2. 배경 / 제약

### 현재 코드 (grep 검증 완료, 2026-07-04)

- **서명/검증 = HS256 대칭키**: `JwtTokenSigner`(user-service `global.jwt`) 가 `Keys.hmacShaKeyFor` 로 서명, `JwtTokenVerifier`(common-auth) 가 동일 대칭키로 검증. 양쪽 단일 `JwtAuthProperties`(`app.jwt.secret/accessTokenExpiry/refreshTokenExpiry`) 바인딩(ADR-0014 D1-b).
- **검증 위치 = 매 서비스 in-process**: `JwtFilter`(common-auth) 가 요청마다 서명검증 + `TokenBlacklistLookupPort.isBlacklisted(token)` 확인. `JwtSecurityConfigurer.apply(http, publicUrls)` 를 5서비스 SecurityConfig(User/Product/Order/Payment/Notification) 가 전부 위임 소비. **Gateway 없음.**
- **blacklist**: 읽기 = common-auth `RedisTokenBlacklistLookupAdapter`(모든 서비스). 쓰기 owner = user-service `AuthService`/`TokenBlacklistRepository`(`TokenBlacklistPort`), `addGracePeriod` 로 rotation 동시요청 이중발급 방지.
- **RefreshToken 엔티티** = id/userId/token/expiresAt(`user.domain.model.RefreshToken`). `AuthService.refresh` = **삭제 기반** rotation(grace 10초). **family_id/status/reuse 감지 없음**(ADR-0013 C1).
- **X-User-* 헤더 신뢰 코드 없음**(현 grep hit 은 Kafka trace 헤더로 무관). **spring-cloud-gateway 의존 없음.**
- **k8s**: `k8s/base/{infra,services}` + `overlays/{gke,minikube}`. 현 canonical 서비스 이미지 5개.

### B1 — 역의존 스윕 (header-trust 전환이 대체하는 인증 검증 seam)

> PR3 에서 "리소스 서비스가 JWT 를 재검증한다"(현재) → "Gateway 만 검증, 서비스는 X-User-* 헤더 신뢰"(목표)로 바꾼다. seam 을 밖에서 참조하는 인바운드 간선과 처분:

| 인바운드 간선 (참조처) | 현재 결합 | 처분 (PR3) |
|---|---|---|
| `JwtSecurityConfigurer.apply()` — User/Product/Order/Payment/Notification 5 SecurityConfig | crypto-verify 정책 위임 | **디커플**: common-auth 에 `HeaderTrustSecurityConfigurer` 신설, 5서비스가 그것으로 전환 |
| `JwtFilter`(crypto verify + blacklist) — common-auth, 5서비스 필터체인 | 서명검증 + blacklist 재확인 | **대체**: `HeaderAuthenticationFilter`(X-User-Id/Role 신뢰, 서명검증 없음, 헤더 누락 시 401). 리소스 서비스 blacklist 재검증 제거(Gateway 소유) |
| `TokenBlacklistLookupPort`/`RedisTokenBlacklistLookupAdapter` — common-auth, 5서비스 | 서비스별 blacklist 읽기 | **확장(PR2)→이동(PR3)**: PR2 에서 family deny 읽기 확장(전환기 enforcement, P9) 후, blacklist/deny 확인 = Gateway 소유로 이관. 리소스 서비스는 미확인(헤더 신뢰). read adapter 는 Gateway 전용화 |
| `UserSecurityIntegrationTest`·`NotificationSecurityIntegrationTest` (통합) | Bearer 토큰으로 인증 프록시 | **재작성**: X-User-* 헤더 인증으로. header-trust 필터 검증 |
| `AuthControllerTest`/`UserControllerTest`/`ProductControllerTest`/`AdminProductControllerTest`/`OrderControllerTest`/`CartControllerTest`/`PaymentControllerTest`/`NotificationControllerTest` (슬라이스) | Bearer/mock 인증 | **재작성(B1b 프록시)**: 컴파일러가 못 잡음. 슬라이스가 인증을 X-User-* 로 세우도록 |
| `JwtFilterTest`(common-auth) | crypto 필터 단위 | **처분**: RS256 검증은 Gateway 로 이관 → Gateway 검증 필터 테스트로 이동/재작성 |
| `AuthService`/`TokenBlacklistPort`/`TokenBlacklistRepository`(user) | blacklist **쓰기** owner | **유지 + 확장**(PR2): family/session deny 쓰기 추가. 쓰기 owner 는 User 유지 |

- **string-level(B1b)**: 리소스 서비스의 `/api/v1/{domain}` permitAll·actuator 노출은 Gateway 라우팅 뒤에서도 유지(내부 direct ingress 는 NetworkPolicy 로 차단). Gateway route path 리터럴이 서비스 URL prefix 와 정합하는지 스윕.

### B2 — ADR 타깃 ≠ 현재 코드 (없는 것은 "만든다"로 승격)

- **Gateway 모듈**: 없음 → PR3 신설(P11). **JWKS endpoint**: 없음 → PR1 신설(P4). **family_id/status 스키마**: 없음 → PR2 마이그레이션(P6). **NetworkPolicy**: 없음 → PR3 신설(P17). **RS256 키 로딩**: 없음 → PR1 신설(P1). 모두 명시 작업 항목으로 승격됨.

### B6 — 새 gateway 모듈이 `:common` 스캔으로 떠안는 횡단 빈

- Gateway 는 WebFlux(reactive) 기반 → `:common`/`:peekcart-common-auth`(servlet MVC·`spring-boot-starter-web`·`JwtFilter`(OncePerRequestFilter)) 를 그대로 의존하면 **servlet↔reactive 충돌**. Gateway 는 `:common` 의 payload/response DTO·에러코드만 선택 의존하고, MVC/servlet 필터·`JwtSecurityConfigurer` 는 **의존 금지**. 검증 컴포넌트는 Gateway 전용(reactive) 로 별도 구현. build.gradle 의존 최소화 명시(P11).

### 트레이드오프

- **Gateway SPOF**: 인증 경로 집중 → HA 다중 인스턴스(HPA) 필수(P17). Redis deny/RateLimiter **fail-closed**(보안 우선, 가용성 영향 수용, ADR-0013 D3).
- **헤더 신뢰 리스크**: NetworkPolicy/헤더 strip 이 깨지면 spoofing. 외부 유입 X-User-* **항상 제거 후 재주입** + 내부 direct ingress 거부가 핵심(P12/P17).
- **키 회전 운영**: dual-validation 기간(active/previous overlap > access TTL) 관리. PR4 에서 HS256 fallback 제거(P21).
- **replay/deny bounded risk**: family deny 미기록 access token 은 짧은 TTL 까지 bounded(ADR-0013 D4 Consequences).

## 3. 작업 항목

### PR1 — RS256 비대칭키 전환 + JWKS (D1/D2, dual-validation)

- [ ] **P1.** RS256 키 로딩: `JwtKeyProperties`(`app.jwt.rs256.*` — active `kid`·개인키 PEM 경로·공개키 목록{kid→PEM}). **파일 마운트**(환경변수 금지, ADR-0007/D2). GKE = Secret Manager CSI 마운트(k8s 배선은 PR3 P17 과 함께). **테스트 키쌍 물리 소유(B5)**: user-service(서명)·common-auth(검증)·PR3 gateway(JWKS 검증) 다중 소비 → 단일 소유 위치 = **`:common` testFixtures 리소스**(모든 모듈이 `:common` 의존). 각 모듈은 복제 대신 testFixtures 로 참조(공개키는 gateway JWKS stub 도 동일 소스 사용).
- [ ] **P2.** `JwtTokenSigner` RS256 서명: `signWith(privateKey, RS256)` + JWT 헤더 `kid` 세팅. 발급은 즉시 RS256 단일 전환.
- [ ] **P3.** `JwtTokenVerifier` dual-validation: 토큰 `kid` 로 공개키 선택 → RS256 검증. **alg allow-list(RS256만)** — `kid` 부재/unknown, alg=none/HS256 위조 거부. 전환기 HS256 fallback(bounded, active/previous overlap 설정) — PR4 제거.
- [ ] **P4.** User JWKS endpoint `/.well-known/jwks.json`: 공개키(kid/kty/n/e) 노출. presentation 계층 + permitAll(공개 URL).
- [ ] **P5.** PR1 테스트 + latency 측정: RS256 서명↔검증 왕복, kid 선택, alg allow-list 거부(HS256 위조·unknown kid·none alg), JWKS 응답 스키마, dual-validation(HS256 fallback on/off). **+ RS256 서명 latency p50/p95 측정**(ADR-0013 D2 후속 조건 — 테스트/로컬 키 기준) → Cloud KMS 비대칭 서명 격상 재검토 근거로 기록(격상은 별도 ADR 후보, 측정만·전환 미결정).

### PR2 — Refresh Token Reuse Detection (D4)

> PR2 착수 보강 (2026-07-06, code-verified): 마이그레이션 번호·평문 token 처분·Redis grace 경로 처분·TokenIssuer 시그니처 seam 을 현재 코드 대조로 확정.

- [ ] **P6.** `refresh_tokens` 마이그레이션(user-service **`V2__`** — 현재 `V1__init_user.sql` 단일): `family_id`·`token_hash`·`status`(ACTIVE/ROTATED/REVOKED)·`rotated_at`·`grace_until`·`replaced_by_token_id` 추가. 삭제 기반 → 상태전이. **`token_hash CHAR(64) NOT NULL` + `UNIQUE KEY uk_refresh_tokens_token_hash(token_hash)`**(드롭하는 `uk_refresh_tokens_token` 의 대체 unique — 중복 시 findByTokenHash 모호성·reuse 오판 차단). 해시 알고리즘 = **common-auth `TokenHasher.sha256Hex` 재사용**(blacklist 신키와 동일 유틸, 키스킴 드리프트 차단). 조회 인덱스(`family_id`). **평문 `token` 컬럼 + `uk_refresh_tokens_token` 드롭**(token_hash 대체·전량 무효화라 backfill 불요). `fk_refresh_tokens_user` 는 도메인 내 FK 로 **유지**. **기존 데이터 처분(명시)**: 그린필드(보존 prod 이력 없음, impl② 선례)라 **기존 `refresh_tokens` row 전량 무효화(재로그인 요구)** 채택 — V2 에서 기존 row **전량 삭제** 후 컬럼 재구성. 기존 `token`(평문 UUID)을 해시해 `token_hash`/`family_id=신규`/`status=ACTIVE` 로 backfill 하는 경로는 *기술적으로 가능하나* 보존 요구가 없어 채택하지 않고 전량 만료로 단순화. access token 은 짧은 TTL 로 자연 소멸.
- [ ] **P7.** `RefreshToken` 엔티티 + repository 확장: 상태전이 rotation, family 단위 조회/무효화, token_hash 조회(평문 미저장). 현 `RefreshTokenRepository`(findByToken/deleteByToken/deleteByUserId/save) → 상태전이 모델로 재정의(`findByTokenHash`·`revokeFamily`·`revokeAllByUserId` 등). **grace 원자 소비 메서드 `consumeGraceOnce(tokenHash, now)`**: 조건부 UPDATE(`status='ROTATED' AND grace_until > now` 인 한 행만 consume 마킹, affected rows=1 만 성공) — 현행 Redis `GETDEL` 원자성(TokenBlacklistRepository)과 동등 보장을 DB 로 이전. 동시 refresh 2건이 둘 다 grace 유효를 읽는 이중 발급 차단. **grace 성공 후 상태 불변식**: 평문 미저장이라 첫 rotation 의 replacement token 을 반환할 수 없음 → 새 token 발급 시 **같은 트랜잭션에서 `replaced_by_token_id` 의 기존 replacement 를 ROTATED 처리** → **family 내 ACTIVE 정확히 1개** 유지(요구사항 "재발급 시 기존 토큰 즉시 무효화" 정합). **force-rotation 비순환 계약**: supersede 되는 replacement 는 **ROTATED-without-grace**(`grace_until` 미부여 또는 `≤ now` — `consumeGraceOnce` 조건 `grace_until > now` 에 다시 걸리는 상태전이 순환 금지), `replaced_by_token_id` 는 새 token id 로 **단방향** 설정(자기참조/순환 금지) → 재제시 즉시 거부(reuse 판정). **supersede 된 replacement 의 access token 처분**: family deny 는 새 토큰까지 차단하므로 정상 grace 경로에 사용 불가 → **access TTL 까지 bounded overlap 으로 수용**(ADR-0013 D4 Consequences 의 bounded risk 와 동일 계열, 즉시 무효화는 미채택 — jti 단위 blacklist 는 과설계).
- [ ] **P8.** `AuthService.refresh` 재작성: **grace**(정상 동시요청 = `grace_until` 내 1회성 허용 — **P7 `consumeGraceOnce` 원자 소비로만 통과**, 조회-후-판단 금지) vs **reuse**(grace 초과 + ROTATED/REVOKED 재제시, 또는 이미 revoked family 재제시) → **family 전체 무효화**. **Redis grace 경로 처분(제거)**: 현행 grace 는 Redis 기반(`TokenBlacklistPort.addGracePeriod`/`consumeGracePeriod` + `AuthService.refreshViaGracePeriod`) — DB `grace_until` 상태전이로 대체하고 port 메서드 2종·Redis 구현·`refreshViaGracePeriod` 를 제거(blacklist `addToBlacklist` 는 유지). **콜사이트 처분**: `login`/`logout` 의 `deleteByUserId` → family REVOKED 상태전이로 전환(row 삭제는 retention 소관이 아닌 user-service 정책 — 삭제 대신 상태 보존이 reuse 감지 증거 유지에 필수).
- [ ] **P9.** family/session deny → Redis 기록(Gateway blacklist source): reuse 감지 시 family deny 키 write(User 소유). **deny 키 계약(ADR-0014 D1-c 준거, PR3 Gateway 가 같은 계약을 읽음)**: 키 = `auth:deny:family:<familyId>`(blacklist 신키 `auth:blacklist:<sha256hex>` 와 동일 네임스페이스 계열, familyId 는 claim 노출 식별자라 해시 불요·토큰 원문 저장 금지), TTL ≥ access token 최대 잔여 TTL(= `accessTokenExpiry`, family 내 마지막 발급 기준 상한), value = 감지 시각(디버깅용, 판정은 키 존재로), write owner = User·read owner = **전환기 common-auth(PR2)** → Gateway(PR3 이관)·miss = 통과·조회 실패 = fail-closed. **전환기 enforcement(PR2, ADR-0013 D4 "즉시 차단" 충족)**: common-auth `TokenBlacklistLookupPort`/`RedisTokenBlacklistLookupAdapter` 를 family deny 확인으로 확장(예: `isBlacklistedOrFamilyDenied(token, familyId)`), `JwtFilter` 가 파싱한 `family_id` claim 을 전달 — PR3 에서 read 경로 전체가 Gateway 로 이관되는 B1 표 동일 행이라 버려지는 작업 아님. 현행 blacklist 조회는 이미 fail-closed(어댑터 확인) — posture 변화 아님. **family_id 부재 계약(전환기 레거시 토큰)**: PR2 배포 전 발급 유효 access token(HS512 fallback 포함)은 family_id claim 없음 — claim 부재는 Redis "조회 실패"(fail-closed)가 아님 → **absent/null/blank 면 blacklist 만 검사, family deny 는 miss 취급**(NPE·`auth:deny:family:null` 오조회·레거시 전면 401 금지). PR2 이후 신규 발급 토큰은 family_id **필수**. access token 에 `family_id` claim 추가 — **`TokenIssuer.issue(userId, role)` 시그니처 확장 seam**: family_id 는 AuthService 가 생성(login/signup=신규)·승계(refresh=기존 family) 하므로 issue 파라미터로 전달. `TokenClaims` record 필드 추가 → common-auth `JwtTokenVerifier.parseToken` 매핑 + 생성자 사용처 컴파일 sweep(record 라 컴파일러가 잡음).
- [ ] **P10.** PR2 테스트: rotation 상태전이, grace 1회 허용/2회 차단, **동일 ROTATED 토큰 병렬 2요청 중 정확히 1건만 성공(consumeGraceOnce 원자성, Testcontainers MySQL)**, reuse 감지→family revoke→Redis deny write, **deny 어댑터 키 계약 검증(prefix `auth:deny:family:`·TTL·원문 미포함)**, **전환기 enforcement: family deny hit→401·miss→통과·Redis 조회 실패→fail-closed(`JwtFilter`+adapter)**, **family_id 부재 레거시 토큰 회귀(RS/HS fallback 각각): NPE 없이 blacklist miss→통과·hit→거부(family deny 미조회)**, **grace 성공 후 family 내 ACTIVE 정확히 1개 + supersede 된 replacement 재제시 즉시 거부(ROTATED-without-grace — consumeGraceOnce 재성공 순환 없음 확증)**, family_id claim 왕복, revoked family 재제시 거부, **common-auth 회귀: `JwtTokenVerifierTest`(family_id claim 매핑)·`JwtFilterTest`(`TokenClaims` 생성자 회귀 + family deny 3분기)**. **+ 마이그레이션 회귀**: Flyway 마이그레이션 적용 후 기존 row 전량 무효화 확인 + `uk_refresh_tokens_token_hash` unique index 존재 검증 + 기존(만료된) refresh token 재제시 시 `AuthService.refresh` 가 안전 거부(NPE/오통과 없음).

### PR3 — Spring Cloud Gateway 모듈 + header-trust 전환 (D3)

- [ ] **P11.** `gateway` 모듈 신설: `settings.gradle` include, `build.gradle`(`spring-cloud-starter-gateway`(webflux)·`data-redis-reactive`·`:common` payload/에러코드만 — B6 servlet 의존 금지). 5서비스 path 라우팅(`/api/v1/{domain}/**`).
- [ ] **P12.** Gateway JWT 검증 필터(reactive): ① 서명/만료(JWKS 공개키, kid) → ② blacklist + family/session deny(Redis, **조회 실패 시 fail-closed** 401/503+alert) → ③ 외부 유입 `X-User-*` **strip 후** `X-User-Id`/`X-User-Role` 주입. **JWKS client 운영(ADR-0013 D1 line 28-29)**: cache TTL 적용, unknown `kid` 도착 시 refresh, **refresh 실패 시 마지막 정상 키 유지(last-known-good) + alert**. 전환기엔 Gateway 검증기도 dual-validation(RS256 + bounded HS256 fallback) — PR4 에서 종료.
- [ ] **P13.** Redis RateLimiter(route-class별): 로그인/refresh(인증 전)=IP+계정, 인증 API(인증 후)=userId, 공개 조회=IP/route. `RequestRateLimiter`(replenish/burst). **Redis 장애 fail-closed**. 429 metric/log owner=Gateway.
- [ ] **P14.** 리소스 서비스 header-trust 전환: common-auth `HeaderAuthenticationFilter`(X-User-* 신뢰, 서명검증 없음, 누락 시 401) + `HeaderTrustSecurityConfigurer`. 5서비스 SecurityConfig 를 `JwtSecurityConfigurer`→header-trust 로 전환, blacklist 재검증 제거(**B1 표 처분**).
- [ ] **P15.** 인증 테스트 재작성(B1b 프록시): 5서비스 SecurityConfig 통합테스트 + 8개 컨트롤러 슬라이스가 Bearer→X-User-* 헤더로. `JwtFilterTest` → Gateway 검증 필터 테스트로 이관.
- [ ] **P16.** Gateway Dockerfile(ARG SERVICE·멀티모듈 COPY·base digest 고정) + CI 이미지 매트릭스(canonical **6**) + `image-contract-lint` 갱신(6/6).
- [ ] **P17.** k8s gateway: base/overlays deployment/svc/cm/secret + **NetworkPolicy**(리소스 서비스 direct ingress 거부, Gateway pod/SA 만 허용) + RS256 키 Secret Manager CSI 마운트(P1 배선) + **HPA**(SPOF→HA). Gateway 전용 ServiceAccount.
- [ ] **P18.** PR3 테스트: 라우팅, JWT 검증 3단계(fail-closed 401/503, JWKS refresh 실패 last-known-good+alert), 헤더 strip+inject(외부 spoof `X-User-*` 제거 확증), rate limit 429, header-trust 필터(헤더 누락 401). **NetworkPolicy 음성·양성**: non-gateway pod → 리소스 서비스 ClusterIP 직접 호출 **실패**, Gateway SA pod → **성공**, 외부 ingress 는 Gateway 만. minikube CNI 제약 시 **GKE overlay 검증을 필수 exit 로 분리**(렌더-only 불충분).

### PR4 — 관측성 S9 + HS256 잔재 제거 (D5)

- [ ] **P19.** S9 auth_failure 메트릭: 인증실패(서명오류/만료)·인가실패(403)·reuse 감지·429 counter(+사유 태그). SSOT = Gateway(인증필터/RateLimiter) + User(reuse/logout). 이름 1개소·이동/복제 금지.
- [ ] **P20.** S9 관측성 구현(ADR **본문 수정 금지** — ADR-0009:58 에 S9 행 **이미 존재**, README:8-15 immutable): (a) S9 owner(gateway/user-service) **존재·정합 검증** + ADR-0015:42 per-service lint 정합 + Layer1(`02`/`04`) **현재 상태 동기화**. (b) S9 계약 자체를 바꾸는 경우에만 신규 ADR/Status 절차로 분리. dashboard `$application` 변수 + alert per-service + `observability lint` 갱신(canonical 6). **B11 sweep**: 식별자 치환은 `application=\?"..."` **및** `service=\?"..."` 형제 라벨 양쪽 + JSON/embedded-YAML escaped/unescaped 양쪽 sweep, 브랜드 문자열(`peekcart-*` uid/tag) 제외.
- [ ] **P21.** HS256 fallback 제거(overlap 만료 후, dual-validation 종료): **양쪽** — common-auth `JwtTokenVerifier` **및 Gateway 검증기** RS256 단일화, `JwtAuthProperties.secret`·HS256 잔재 sweep.
- [ ] **P22.** PR4 테스트: 메트릭 counter 통합테스트(사유 태그별), observability lint **negative**(canonical 6 불일치·escaped-quote 잔존 false-green 차단), HS256 제거 후 부팅/검증 회귀(common-auth + Gateway).

## 4. 영향 파일

- **신규 모듈**: `gateway/**`(build.gradle·routing config·검증 필터·RateLimiter·Dockerfile), `settings.gradle`.
- **PR1**: `user-service/global/jwt/JwtTokenSigner.java`, `peekcart-common-auth/global/jwt/JwtTokenVerifier.java`·`JwtAuthProperties.java`(→`JwtKeyProperties` 신규), User JWKS controller, **테스트 키쌍 = `:common` testFixtures 리소스**(단일 소유, B5).
- **PR2**: `user-service` `refresh_tokens` 마이그레이션(V2)·`RefreshToken.java`·`RefreshTokenRepository(Impl/Jpa)`·`AuthService.java`·`TokenBlacklistPort`/deny 확장·`TokenClaims`(family_id)·common-auth `JwtTokenVerifier`(claim 매핑)·`JwtFilter`/`TokenBlacklistLookupPort`/`RedisTokenBlacklistLookupAdapter`(전환기 family deny enforcement)·`JwtTokenSigner`/`TokenIssuer`(issue 시그니처).
- **PR3**: `peekcart-common-auth/global/security/`(HeaderAuthenticationFilter·HeaderTrustSecurityConfigurer)·5서비스 `*SecurityConfig.java`·인증 테스트 8+2개·`k8s/base/services/gateway/**`·NetworkPolicy·CI images 매트릭스·image-contract lint.
- **PR4**: Gateway/User 메트릭 컴포넌트·grafana dashboard/alert·observability lint·`02`/`04` Layer1 동기화. **ADR 본문은 수정 안 함**(S9 는 ADR-0009:58 기존재) — 존재/owner 정합만 검증, 계약 변경 시에만 신규 ADR.

## 5. 검증 방법

- **PR1**: `./gradlew :user-service:test :peekcart-common-auth:test` — RS256 왕복·alg 거부·JWKS 스키마 그린. HS256 위조 토큰 401.
- **PR2**: `./gradlew :peekcart-common-auth:test :user-service:test` — grace 1회/2회 차단·병렬 1건만 성공, reuse→family revoke→Redis deny write 확증(Testcontainers Redis), common-auth `TokenClaims`/parseToken family_id 회귀.
- **PR3**: `./gradlew build test`(9모듈) — gateway 라우팅·3단계 검증·fail-closed·헤더 strip/inject·rate limit 429·header-trust 필터. **NetworkPolicy 음성·양성**(non-gateway pod 직접호출 실패 / Gateway SA 성공 / 외부 spoof strip) — minikube CNI 제약 시 **GKE overlay 검증 필수 exit**(렌더-only 불충분).
- **PR4**: 메트릭 counter 통합테스트(사유 태그)·observability lint negative·HS256 제거 회귀. 전 모듈 그린.
- **가드**: `assertNoServiceProjectDeps`(gateway↔서비스 직접 의존 금지), B1b string-level sweep(route path↔서비스 prefix).

## 6. 완료 조건

- [ ] RS256 서명↔JWKS 검증 왕복, HS256/none/unknown-kid 거부. (PR1)
- [ ] reuse 재제시 → family 전체 무효화 + Redis family deny → 이미 발급된 access token 즉시 차단 확증(PR2 = 전환기 common-auth enforcement, PR3 = Gateway 이관). (PR2/PR3)
- [ ] Gateway 통해서만 인증 통과, 리소스 서비스 direct ingress 거부(헤더 신뢰·NetworkPolicy). 외부 유입 X-User-* spoof 제거. (PR3)
- [ ] Rate limit route-class별 429 + fail-closed. (PR3)
- [ ] S9 auth_failure 메트릭 + ADR-0009 S9 행 + observability lint 6/6. (PR4)
- [ ] HS256 fallback 제거 후 9모듈 그린, 인증 회귀 0. (PR4)
- [ ] 보안 묶음 L-001/002/003/019 종결, ADR-0013 구현 완료.

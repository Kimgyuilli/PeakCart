# task-impl3-spring-cloud-gateway — 구현 ③ Spring Cloud Gateway (RS256 + Reuse Detection + S9)

> 선행 ADR-0013(Accepted). 보안 묶음 L-001/002/003/019 편입. Phase 4 구현 로드맵 ③.
> PR 분할: PR1 RS256/JWKS(dual-validation) → PR2 Refresh Reuse Detection → PR3 Gateway 모듈 + header-trust 전환(+ ADR-0014 D2-c servlet 검증 exit) → PR4 관측성 S9 + HS512 잔재 제거.
> 각 PR 은 자체 `/work` + `/ship` 을 거치며, 해당 PR 착수 시 세부 계획을 보강한다(impl② PR3 선례).

## 1. 목표

- **RS256 비대칭키 전환**(D1): User 만 개인키 서명, Gateway 가 공개키 1차 검증, 리소스 서비스 미재검증(헤더 신뢰). JWKS 공개키 배포.
- **Refresh Token Reuse Detection**(D4): 삭제 기반 rotation → `family_id`/`status` 상태전이. reuse(탈취) 감지 시 family 전체 무효화 + Redis family/session deny(access token 즉시 차단).
- **Spring Cloud Gateway**(D3): 5서비스 path 라우팅, JWT 검증 3단계(서명→blacklist/deny fail-closed→신뢰 헤더 주입), Redis RateLimiter(route-class별).
- **관측성 S9**(D5): 인증실패/reuse/429 메트릭(counter+사유 태그), ADR-0009 S9 행 추가.
- **성공 기준**: 8+1(gateway) 모듈 그린, gateway 통해서만 인증 통과(리소스 서비스 direct ingress 거부 + NodePort/LB 환원), 공개 경로는 무헤더 통과, reuse 재제시→family revoke 확증, RS256 왕복·위조 alg(HS256/HS384/none) 거부.

## 2. 배경 / 제약

### 현재 코드 (grep 검증 완료, 2026-07-04)

- **서명/검증 = HMAC 대칭키**(당시 "HS256" 으로 기록했으나 512bit secret 이라 **실제 alg 는 HS512** — PR1 구현 중 확인, loop2 #8): `JwtTokenSigner`(user-service `global.jwt`) 가 `Keys.hmacShaKeyFor` 로 서명, `JwtTokenVerifier`(common-auth) 가 동일 대칭키로 검증. 양쪽 단일 `JwtAuthProperties`(`app.jwt.secret/accessTokenExpiry/refreshTokenExpiry`) 바인딩(ADR-0014 D1-b).
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

- **Gateway SPOF**: 인증 경로 집중 → HA 다중 인스턴스(HPA) 필수(P17). Redis deny/RateLimiter **fail-closed**(보안 우선, 가용성 영향 수용, ADR-0013 D3). **blast radius**: Redis 장애 시 deny 조회와 RateLimiter 가 동시 fail-closed → 인증 경로뿐 아니라 **공개 경로도 503**(P13 에 운영 문서화).
- **헤더 신뢰 리스크**: NetworkPolicy/헤더 strip 이 깨지면 spoofing. 외부 유입 X-User-* **항상 제거 후 재주입**(공개 경로 포함) + 내부 direct ingress 거부 + **기존 NodePort/LB 노출 환원**이 핵심(P12/P17). 전환 도중이 가장 취약 → 롤아웃 순서 고정(P18).
- **키 회전 운영**: dual-validation 기간(active/previous overlap > access TTL) 관리. PR4 에서 **HS512** fallback 제거(P22).
- **계약 이중 구현(servlet↔reactive)**: `:common` 이 servlet 을 `api` 로 전이 노출해 Gateway 가 재사용 불가(보강 e) → alg/kid/claims/Redis-key 계약이 두 곳에 존재. **conformance 테스트로만 동등성 보장**(P19), PR3 P14 에서 servlet 측이 삭제되면 자연 해소.
- **replay/deny bounded risk**: family deny 미기록 access token 은 짧은 TTL 까지 bounded(ADR-0013 D4 Consequences).

## 3. 작업 항목

### PR1 — RS256 비대칭키 전환 + JWKS (D1/D2, dual-validation)

- [ ] **P1.** RS256 키 로딩: `JwtKeyProperties`(`app.jwt.rs256.*` — active `kid`·개인키 PEM 경로·공개키 목록{kid→PEM}). **파일 마운트**(환경변수 금지, ADR-0007/D2). GKE = Secret Manager CSI 마운트(k8s 배선은 PR3 P17 과 함께). **테스트 키쌍 물리 소유(B5)**: user-service(서명)·common-auth(검증)·PR3 gateway(JWKS 검증) 다중 소비 → 단일 소유 위치 = **`:common` testFixtures 리소스**(모든 모듈이 `:common` 의존). 각 모듈은 복제 대신 testFixtures 로 참조(공개키는 gateway JWKS stub 도 동일 소스 사용).
- [ ] **P2.** `JwtTokenSigner` RS256 서명: `signWith(privateKey, RS256)` + JWT 헤더 `kid` 세팅. 발급은 즉시 RS256 단일 전환.
- [ ] **P3.** `JwtTokenVerifier` dual-validation: 토큰 `kid` 로 공개키 선택 → RS256 검증. **alg allow-list(RS256만)** — `kid` 부재/unknown, alg=none/HS256/HS384 위조 거부. 전환기 **HS512** fallback(레거시 alg 정확 한정·bounded·기본 off) — PR4 제거. *(구현 결과 정정: 레거시 토큰의 실제 alg 는 HS512 — `JwtTokenVerifier:90-95`)*
- [ ] **P4.** User JWKS endpoint `/.well-known/jwks.json`: 공개키(kid/kty/n/e) 노출. presentation 계층 + permitAll(공개 URL).
- [ ] **P5.** PR1 테스트 + latency 측정: RS256 서명↔검증 왕복, kid 선택, alg allow-list 거부(HS256/HS384 위조·unknown kid·none alg), JWKS 응답 스키마, dual-validation(**HS512** fallback on/off). **+ RS256 서명 latency p50/p95 측정**(ADR-0013 D2 후속 조건 — 테스트/로컬 키 기준) → Cloud KMS 비대칭 서명 격상 재검토 근거로 기록(격상은 별도 ADR 후보, 측정만·전환 미결정).

### PR2 — Refresh Token Reuse Detection (D4)

> PR2 착수 보강 (2026-07-06, code-verified): 마이그레이션 번호·평문 token 처분·Redis grace 경로 처분·TokenIssuer 시그니처 seam 을 현재 코드 대조로 확정.

- [ ] **P6.** `refresh_tokens` 마이그레이션(user-service **`V2__`** — 현재 `V1__init_user.sql` 단일): `family_id`·`token_hash`·`status`(ACTIVE/ROTATED/REVOKED)·`rotated_at`·`grace_until`·`replaced_by_token_id` 추가. 삭제 기반 → 상태전이. **`token_hash CHAR(64) NOT NULL` + `UNIQUE KEY uk_refresh_tokens_token_hash(token_hash)`**(드롭하는 `uk_refresh_tokens_token` 의 대체 unique — 중복 시 findByTokenHash 모호성·reuse 오판 차단). 해시 알고리즘 = **common-auth `TokenHasher.sha256Hex` 재사용**(blacklist 신키와 동일 유틸, 키스킴 드리프트 차단). 조회 인덱스(`family_id`). **평문 `token` 컬럼 + `uk_refresh_tokens_token` 드롭**(token_hash 대체·전량 무효화라 backfill 불요). `fk_refresh_tokens_user` 는 도메인 내 FK 로 **유지**. **기존 데이터 처분(명시)**: 그린필드(보존 prod 이력 없음, impl② 선례)라 **기존 `refresh_tokens` row 전량 무효화(재로그인 요구)** 채택 — V2 에서 기존 row **전량 삭제** 후 컬럼 재구성. 기존 `token`(평문 UUID)을 해시해 `token_hash`/`family_id=신규`/`status=ACTIVE` 로 backfill 하는 경로는 *기술적으로 가능하나* 보존 요구가 없어 채택하지 않고 전량 만료로 단순화. access token 은 짧은 TTL 로 자연 소멸.
- [ ] **P7.** `RefreshToken` 엔티티 + repository 확장: 상태전이 rotation, family 단위 조회/무효화, token_hash 조회(평문 미저장). 현 `RefreshTokenRepository`(findByToken/deleteByToken/deleteByUserId/save) → 상태전이 모델로 재정의(`findByTokenHash`·`revokeFamily`·`revokeAllByUserId` 등). **grace 원자 소비 메서드 `consumeGraceOnce(tokenHash, now)`**: 조건부 UPDATE(`status='ROTATED' AND grace_until > now` 인 한 행만 consume 마킹, affected rows=1 만 성공) — 현행 Redis `GETDEL` 원자성(TokenBlacklistRepository)과 동등 보장을 DB 로 이전. 동시 refresh 2건이 둘 다 grace 유효를 읽는 이중 발급 차단. **grace 성공 후 상태 불변식**: 평문 미저장이라 첫 rotation 의 replacement token 을 반환할 수 없음 → 새 token 발급 시 **같은 트랜잭션에서 `replaced_by_token_id` 의 기존 replacement 를 ROTATED 처리** → **family 내 ACTIVE 정확히 1개** 유지(요구사항 "재발급 시 기존 토큰 즉시 무효화" 정합). **force-rotation 비순환 계약**: supersede 되는 replacement 는 **ROTATED-without-grace**(`grace_until` 미부여 또는 `≤ now` — `consumeGraceOnce` 조건 `grace_until > now` 에 다시 걸리는 상태전이 순환 금지), `replaced_by_token_id` 는 새 token id 로 **단방향** 설정(자기참조/순환 금지) → 재제시 즉시 거부(reuse 판정). **supersede 된 replacement 의 access token 처분**: family deny 는 새 토큰까지 차단하므로 정상 grace 경로에 사용 불가 → **access TTL 까지 bounded overlap 으로 수용**(ADR-0013 D4 Consequences 의 bounded risk 와 동일 계열, 즉시 무효화는 미채택 — jti 단위 blacklist 는 과설계).
- [ ] **P8.** `AuthService.refresh` 재작성: **grace**(정상 동시요청 = `grace_until` 내 1회성 허용 — **P7 `consumeGraceOnce` 원자 소비로만 통과**, 조회-후-판단 금지) vs **reuse**(grace 초과 + ROTATED/REVOKED 재제시, 또는 이미 revoked family 재제시) → **family 전체 무효화**. **Redis grace 경로 처분(제거)**: 현행 grace 는 Redis 기반(`TokenBlacklistPort.addGracePeriod`/`consumeGracePeriod` + `AuthService.refreshViaGracePeriod`) — DB `grace_until` 상태전이로 대체하고 port 메서드 2종·Redis 구현·`refreshViaGracePeriod` 를 제거(blacklist `addToBlacklist` 는 유지). **콜사이트 처분**: `login`/`logout` 의 `deleteByUserId` → family REVOKED 상태전이로 전환(row 삭제는 retention 소관이 아닌 user-service 정책 — 삭제 대신 상태 보존이 reuse 감지 증거 유지에 필수).
- [ ] **P9.** family/session deny → Redis 기록(Gateway blacklist source): reuse 감지 시 family deny 키 write(User 소유). **deny 키 계약(ADR-0014 D1-c 준거, PR3 Gateway 가 같은 계약을 읽음)**: 키 = `auth:deny:family:<familyId>`(blacklist 신키 `auth:blacklist:<sha256hex>` 와 동일 네임스페이스 계열, familyId 는 claim 노출 식별자라 해시 불요·토큰 원문 저장 금지), TTL ≥ access token 최대 잔여 TTL(= `accessTokenExpiry`, family 내 마지막 발급 기준 상한), value = 감지 시각(디버깅용, 판정은 키 존재로), write owner = User·read owner = **전환기 common-auth(PR2)** → Gateway(PR3 이관)·miss = 통과·조회 실패 = fail-closed. **전환기 enforcement(PR2, ADR-0013 D4 "즉시 차단" 충족)**: common-auth `TokenBlacklistLookupPort`/`RedisTokenBlacklistLookupAdapter` 를 family deny 확인으로 확장(예: `isBlacklistedOrFamilyDenied(token, familyId)`), `JwtFilter` 가 파싱한 `family_id` claim 을 전달 — PR3 에서 read 경로 전체가 Gateway 로 이관되는 B1 표 동일 행이라 버려지는 작업 아님. 현행 blacklist 조회는 이미 fail-closed(어댑터 확인) — posture 변화 아님. **family_id 부재 계약(전환기 레거시 토큰)**: PR2 배포 전 발급 유효 access token(HS512 fallback 포함)은 family_id claim 없음 — claim 부재는 Redis "조회 실패"(fail-closed)가 아님 → **absent/null/blank 면 blacklist 만 검사, family deny 는 miss 취급**(NPE·`auth:deny:family:null` 오조회·레거시 전면 401 금지). PR2 이후 신규 발급 토큰은 family_id **필수**. access token 에 `family_id` claim 추가 — **`TokenIssuer.issue(userId, role)` 시그니처 확장 seam**: family_id 는 AuthService 가 생성(login/signup=신규)·승계(refresh=기존 family) 하므로 issue 파라미터로 전달. `TokenClaims` record 필드 추가 → common-auth `JwtTokenVerifier.parseToken` 매핑 + 생성자 사용처 컴파일 sweep(record 라 컴파일러가 잡음).
- [ ] **P10.** PR2 테스트: rotation 상태전이, grace 1회 허용/2회 차단, **동일 ROTATED 토큰 병렬 2요청 중 정확히 1건만 성공(consumeGraceOnce 원자성, Testcontainers MySQL)**, reuse 감지→family revoke→Redis deny write, **deny 어댑터 키 계약 검증(prefix `auth:deny:family:`·TTL·원문 미포함)**, **전환기 enforcement: family deny hit→401·miss→통과·Redis 조회 실패→fail-closed(`JwtFilter`+adapter)**, **family_id 부재 레거시 토큰 회귀(RS/HS fallback 각각): NPE 없이 blacklist miss→통과·hit→거부(family deny 미조회)**, **grace 성공 후 family 내 ACTIVE 정확히 1개 + supersede 된 replacement 재제시 즉시 거부(ROTATED-without-grace — consumeGraceOnce 재성공 순환 없음 확증)**, family_id claim 왕복, revoked family 재제시 거부, **common-auth 회귀: `JwtTokenVerifierTest`(family_id claim 매핑)·`JwtFilterTest`(`TokenClaims` 생성자 회귀 + family deny 3분기)**. **+ 마이그레이션 회귀**: Flyway 마이그레이션 적용 후 기존 row 전량 무효화 확인 + `uk_refresh_tokens_token_hash` unique index 존재 검증 + 기존(만료된) refresh token 재제시 시 `AuthService.refresh` 가 안전 거부(NPE/오통과 없음).

### PR3 — Spring Cloud Gateway 모듈 + header-trust 전환 (D3)

> **PR3 착수 보강 (2026-07-23, code-verified + Codex 리뷰 13건 전량 반영)**: PR1/PR2 머지 이후 현재 코드 대조로 아래를 확정. §2 B1 표는 PR3 착수 전 sweep(2026-07-04) 기준이라 PR2 산출물 반영 보정.
>
> **(a) PR2 가 이미 심은 것 (Gateway 가 재사용/이관할 표면)**: `TokenBlacklistLookupPort.isBlacklistedOrFamilyDenied(token, familyId)` + `RedisTokenBlacklistLookupAdapter`(servlet) + `JwtFilter` family deny enforcement + `TokenClaims.familyId` + `RsaPublicKeyRegistry`·`JwtKeyProperties`·`PemKeyLoader`·`JwtTokenVerifier`(RS256·kid) 전부 **존재**. → **P12 Gateway 검증기는 이 read 계약·키 선택 로직을 reactive 로 재구현**(B6: servlet `OncePerRequestFilter`/`RedisTemplate` adapter 직접 재사용 불가). 확정 사항:
>   - **공개키 소스 = User JWKS 정본**(`/.well-known/jwks.json`, ADR-0013 D1:27-29). `RsaPublicKeyRegistry` 로컬 미러 선택지 **폐기**(정본 이원화 금지). 개인키 PEM 은 **user-service Pod 에만** 마운트하고 **Gateway 에는 절대 마운트하지 않는다**(D1/D2 — Gateway 는 검증만).
>   - **전환기 알고리즘 = RS256 + 조건부 HS512**(HS256 아님). 실제 `JwtTokenVerifier.java:90-95` 가 레거시를 **HS512 정확 한정**하고 HS256/HS384 를 거부 → 계획 전반의 "HS256 fallback" 표기를 **HS512** 로 정정(허용면 과확장·잔존 토큰 오거부 동시 방지).
>   - **키 계약 그대로 재사용**(새 스킴 금지): `auth:blacklist:<sha256hex>`·`auth:deny:family:<familyId>`. **레거시 `bl:<raw-token>` dual-read 유지**(`RedisTokenBlacklistLookupAdapter:14-19,43-49`) — access token 최대 TTL 경과를 증명한 명시적 제거 게이트 전까지 존속.
>
> **(b) `LoginUser.accessToken()` seam 파손 → 계약 확정**: `@CurrentUser LoginUser` resolver 가 `authentication.getDetails()`(= `JwtFilter` 가 넣는 raw access token)를 노출. **유일 소비자 = `AuthController.logout:53-56` → `authService.logout(accessToken)`(sha256 blacklist, `AuthService:87-98`)**. header-trust 하에선 리소스 서비스가 raw 토큰 미보유 → logout blacklist 불가. **확정 처분**: Gateway 가 `X-User-Family-Id` 도 주입, `LoginUser(userId, role, familyId)` 로 계약 고정(**nullable accessToken 대안 폐기**), `logout(userId, familyId)` = family deny write + `revokeAllByUserId` (`JwtTokenVerifier` 의존 제거). ADR-0013 D4 family containment(`0013:52-56`) 정합. **`X-Access-Token`/`X-Token-Hash` forward 는 비채택** — 리소스 서비스가 JWT 를 보지 않는다는 D1(`0013:27`) 원칙을 약화.
>
> **(c) 슬라이스 테스트 rewrite 실범위 (P15 정정)**: 8개 컨트롤러 슬라이스는 **Bearer 토큰을 쓰지 않음** — `@WithMockLoginUser`(common testFixtures `WithMockLoginUserSecurityContextFactory`)로 SecurityContext 를 직접 세팅(`TestSecurityConfig` permitAll, 필터 무관). header-trust 는 **필터 메커니즘만 교체**하므로 슬라이스 무영향. **재작성 실범위 = SecurityConfig 통합테스트 2개**(`UserSecurityIntegrationTest`·`NotificationSecurityIntegrationTest`) **+ `JwtFilterTest`**(Gateway reactive 검증 필터로 이관) **+ `WithMockLoginUserSecurityContextFactory`**(principal 에 familyId 추가분 소폭 조정).
>
> **(d) canonical 고정 목록 = 3 스크립트 (P16/P17 정정)**: `scripts/image-contract-lint.sh:44`·`scripts/promote-images.sh:29`(`CANONICAL_SERVICES` 5) **+ `scripts/servicemonitor-selector-lint.sh:93`**(`CANONICAL` 5 집합 **동등 비교** — 6번째 monitor 가 곧 lint 실패). Gateway 는 *도메인* 서비스가 아니라 **인프라 게이트웨이** → 도메인 canonical 5 정본(ADR-0010 §5)을 보존하고 **별도 인프라 목록으로 분리**, 매트릭스/lint 총계는 6.
>
> **(e) `:common` 이 servlet 을 `api` 로 전이 노출 (B6 가 물리적으로 성립)**: `common/build.gradle:12-14` 가 `spring-boot-starter-web`·`data-jpa`·`spring-kafka` 를 **`api`** 로 선언 → gateway 가 `project(':common')` 을 의존하면 WebFlux 런타임에 MVC/servlet + JPA/Kafka 가 그대로 유입(`PLAN-BLINDSPOTS.md:49` 경고와 동일). **확정 처분: gateway 는 `:common` 미의존, 응답 DTO 는 gateway-local.** 순수 DTO 공유 모듈 신설은 ADR 절차가 필요하므로 PR3 범위 밖(필요 시 별도 ADR).
>
> **(f) 기존 서비스 외부 노출이 전환기 상태로 잔존**: minikube **NodePort ×5**(`overlays/minikube/patches/*-service.yml`), GKE **Internal LoadBalancer ×5**(`overlays/gke/patches/*-service.yml`) — 각 patch 주석이 *"gateway ③ 도입 시 단일 ingress 로 통합 예정 — 전환기"* 로 이미 예고. NetworkPolicy 만 얹으면 **외부 노출 잔존**. 동시에 Prometheus 가 각 ServiceMonitor 로 `/actuator/prometheus` 를 **직접 scrape**(`base/services/*/servicemonitor.yml`) → Gateway-only 정책이면 **scrape 차단**. **확정 처분: ClusterIP 환원 + NetworkPolicy 에 monitoring scrape 예외**(P17).
>
> **(g) ADR-0014 D2-c exit 이 PR3 소관 (PR4 아님)**: `docs/adr/0014-transitional-auth-module.md:68-70` 이 **PR3 에서** `JwtFilter`·`JwtTokenVerifier`·blacklist lookup 제거를 요구. 기존 P21/P22 가 common-auth verifier 의 PR4 존속을 전제해 **ADR 과 충돌** → 삭제는 PR3(P14)로 당기고, **PR4 범위는 Gateway 의 HMAC fallback + 잔재 sweep 으로 축소**.

#### PR3 실행 분할 (PR3a~d) — loop2 #2 반영

> 단일 PR/단일 이미지로는 P18 롤아웃의 ④(header-trust 배포)와 ⑤(Authorization 중단·verifier 삭제)를 **구분 배포할 수 없고 역순 롤백도 불가**. 작업 항목(P11~P19)은 그대로 두고 **실행 단계를 분리**한다. 각 단계는 고유 이미지 태그를 갖고, 이전 단계로 되돌릴 수 있어야 한다.

| 단계 | 내용 | 주 항목 | 진입 조건 | 롤백 |
|---|---|---|---|---|
| **PR3a** | Gateway shadow 배포 — 라우팅·검증기·RateLimiter, **Authorization + 재주입 헤더 병행 전달**. 서비스는 아직 `JwtFilter`(구) 유지 | P11·P12·P13·P16 | Redis/JWKS warm-up 완료, Gateway 최초 JWKS 적재 성공(usable key ≥1), readiness=true | Gateway 트래픽 0 (서비스 직접 경로 살아있음) |
| **PR3b** | 트래픽을 Gateway 로 전환 (canary → 100%) | P17 부분(gateway svc/HPA/SA/ServiceMonitor) | PR3a 안정, 보호/공개/spoof canary 통과, 오류율 임계 이하 | 트래픽을 기존 직접 경로로 복귀 |
| **PR3c** | 서비스 직접 노출 제거(ClusterIP)+NetworkPolicy, **header-trust 호환 이미지 rollout**(구·신 Pod 혼재 허용 — 헤더/Bearer 양쪽 수용) | P14 전환분·P17 나머지 | PR3b 100% 전환 유지, 혼재 구간 refresh/logout 정상 | 이전 이미지 태그로 rollout undo, NodePort/LB patch 복구 |
| **PR3d** | **rollback window 경과 후** Authorization 전달 중단 + servlet verifier 삭제(ADR-0014 D2-c exit) | P14 삭제분·P18 ⑤ | family-less 토큰 소멸 증명(#1 게이트), 혼재 Pod 0 | ⚠️ **Gateway 가 Authorization 전달을 먼저 복구**해야 서비스 롤백 가능 — 역순 강제 |

- **rollout 공통**: `maxUnavailable=0`, 단계별 고유 이미지 태그, 각 단계 readiness·오류율 임계·역순 rollback 명령과 증적을 P18/P19 완료 조건으로 고정.

#### 공개 경로 SSOT (method+path) — loop2 #5 반영

> Gateway allowlist ↔ 5서비스 SecurityConfig permitAll ↔ 기대 응답의 **삼자 정본**. 현재 서비스 matcher 는 method 무관 문자열이라(`ProductSecurityConfig:31-36` 이 `/api/v1/products/**` 전체 permitAll) **HttpMethod matcher 로 좁힌다**.

| method + path | Gateway 외부 | 서비스 permitAll | 기대 |
|---|---|---|---|
| `POST /api/v1/auth/signup`·`/login`·`/refresh` | 공개 | 유지 | 무헤더 200 |
| `GET /api/v1/products`·`/api/v1/products/**` | 공개 | **GET 으로 한정**(현재 method 무관 → 정정) | 무헤더 200 |
| `POST /api/v1/products/**`(관리) · `/api/v1/admin/products/**` | 인증 | 보호 | 무헤더 401 / role 부족 403 |
| `POST /api/v1/payments/webhook`(Toss) | 공개 | 유지 | 무헤더 200, **Gateway 경유 확증** |
| `/.well-known/jwks.json` | **미노출** | User 내부 permitAll | **Gateway 외부 404** / User 내부 200 (P19 분리 검증) |
| `/swagger-ui/**`·`/api-docs/**` | **미노출** | 내부 유지 | Gateway 외부 404 |
| `/actuator/**` | 미노출 | S4 소유(ActuatorSecurityConfig) | Prometheus scrape 만 허용(P17 예외) |

- [ ] **P11.** `gateway` 모듈 신설: `settings.gradle` include, `build.gradle`(`spring-cloud-starter-gateway`(webflux)·`data-redis-reactive`). **`:common` 의존 금지(보강 e)** — 응답 DTO/에러코드는 gateway-local 정의. **라우트 정본 표**(placeholder 금지, 실 controller prefix 대조): `/api/v1/auth/**`·`/api/v1/users/**`→user, `/api/v1/products/**`·`/api/v1/admin/products/**`→product, `/api/v1/cart/**`(**단수**)·`/api/v1/orders/**`→order, `/api/v1/payments/**`→payment, `/api/v1/notifications/**`→notification. **`/.well-known/jwks.json` 은 `/api/v1` 밖**(`JwkController:29`) → Gateway 내부 fetch 전용, 외부 라우트 **미노출**(외부 공개 불요). Swagger/api-docs 는 서비스 간 경로 충돌 소지 → **외부 미노출**로 처분.
- [ ] **P12.** Gateway JWT 검증 필터(reactive): ① 서명/만료(**User JWKS 공개키**, kid) → ② blacklist + family deny(Redis, **조회 실패 fail-closed**) → ③ 외부 유입 `X-User-*` **strip 후** `X-User-Id`/`X-User-Role`/`X-User-Family-Id` 주입. **공개 경로는 JWT 미요구**(보강/P14 와 연결): allowlist 경로도 외부 `X-User-*` 는 **항상 strip** 하되 토큰 없으면 그대로 통과. **PR2 read 계약 reactive 재구현(보강 a)**: `isBlacklistedOrFamilyDenied` 시맨틱·키 계약 동일, `bl:` dual-read 유지. **알고리즘 = RS256 + 조건부 HS512**(HS256/HS384/none 상시 거부). **JWKS client 운영(D1:28-29)**: cache TTL, unknown `kid` 도착 시 refresh, refresh 실패 시 last-known-good 유지 + alert.
  - **family-less 토큰 처분 = 시한부(loop2 #1)**: `familyId` absent/null/blank 수용은 **PR3a~c 전환기 한정**. P14 가 principal·`logout(userId, familyId)` 를 familyId 고정으로 잡는데 수용을 영구화하면 `auth:deny:family:null` 오기록(writer `TokenBlacklistRepository:47-49` 가 키에 직결) 또는 혼재 구간 401 중 하나가 터진다. **PR3d 진입 게이트 = "마지막 family-less 토큰 발급 시각 + access token 최대 TTL 경과" 증명** → 게이트 통과 시 수용 경로 제거하고 **`LoginUser.familyId` non-null 불변식** 확정. 전환기에 family-less 토큰은 신·레거시 blacklist 만 조회(family deny 는 miss, fail-closed 대상 아님).
  - **응답 행렬 확정(loop2 #7 — 503↔LKG 분기점)**: known kid + 유효 LKG → JWKS 장애 중에도 **정상 처리 + alert** / unknown kid 이고 refresh 성공 후에도 미존재 → **401** / unknown kid 이고 refresh **실패** → **503** / cold start 에 usable key 0 → **readiness=false**(트래픽 미수신, 503 아님). Redis lookup 실패 → **503**, deny·서명오류·만료 → **401**, role 부족 → **403**, rate limit 초과 → **429**. 이 행렬을 P19 parameterized test 로 고정.
- [ ] **P13.** Redis RateLimiter(route-class별): 로그인/refresh(인증 전)=IP(+계정은 후속), 인증 API(인증 후)=**검증된** userId, 공개 조회=IP/route. **Redis 장애 fail-closed → 503**(P12 응답 계약). 429 metric/log owner=Gateway. **blast radius 명시(운영 문서)**: 공개 경로는 deny 조회 대상이 아니지만 **RateLimiter Redis 장애로는 503** → Redis 장애 시 인증·공개 경로가 동시 차단됨을 운영 문서에 기록.
  - **구현 정정(PR3a, GW-2 c2:1/c3:1/c3:2/c3:3)**: ① SCG 기본 `RedisRateLimiter` 는 Redis 오류를 `allowed=true` 로 삼켜 **fail-OPEN** → `FailClosedRedisRateLimiter`(고정 윈도우 INCR, 오류 전파) 자체 구현으로 대체. ② 인증 필터가 라우트 필터보다 **먼저** 실행돼야(order<1) RateLimiter 가 위조 `X-User-Id` 를 키로 쓰지 않는다 → 검증된 userId 를 exchange attribute 로 전달. ③ `/api/v1/auth/**` 를 통째로 pre-auth 키로 묶으면 인증 API 인 logout 이 섞임 → signup/login/refresh 전용 라우트 분리.
  - **후속(계정 차원 제한)**: 계획의 "IP+계정" 중 **계정 성분은 미구현**. login/signup 이 email 을 **JSON 본문**으로 받아 gateway 가 읽으려면 body-caching decorator 가 필요하다(프록시 경로 개입). PR3a 는 조작 가능한 `?email` 쿼리 성분을 **제거하고 IP 단독**으로 좁혔다(이전 구현은 쿼리만 바꿔 버킷 회피가 가능해 실효가 없었음). 계정 단위 credential-stuffing 방어는 body-caching 도입과 함께 **별도 항목으로 후속** 처리한다.
- [ ] **P14.** 리소스 서비스 header-trust 전환(**PR3c**) + servlet 검증 삭제(**PR3d**): common-auth `HeaderAuthenticationFilter` + `HeaderTrustSecurityConfigurer`, 5서비스 SecurityConfig 를 `JwtSecurityConfigurer`→header-trust 로 전환. **`LoginUser` 계약(보강 b)**: `LoginUser(userId, role, familyId)`, `logout(userId, familyId)`=family deny write + `revokeAllByUserId`, resolver 의 `getDetails()`→accessToken 매핑 제거.
  - **헤더 3-state 계약 확정(loop2 #4)**: ① **세 헤더 모두 없음 = anonymous 로 체인 계속**(즉시 401 금지 — 공개 경로가 깨진다. 보호 경로는 `anyRequest().authenticated()` 가 401 로 막는 것이 확인됨 `JwtSecurityConfigurer:41-44`) / ② 세 헤더가 **각각 정확히 하나씩 존재 + 검증 통과 = 인증** / ③ **그 외(부분 존재·blank·중복 헤더·형식 오류·미허용 role) = 401**. 검증 규칙: `X-User-Id`=양의 정수, `X-User-Role`=명시 enum(USER/ADMIN), `X-User-Family-Id`=non-blank(PR3d 이후 필수). **파싱 예외가 500 이나 anonymous fallback 으로 새지 않게** 한다.
  - **ADR-0014 D2-c exit — 클래스별 move/delete/retain 표(loop2 #3, PR3d 실행)**: 일괄 삭제하면 User 서명/JWKS 가 깨진다(`JwtTokenSigner` 가 `JwtAuthProperties`/`JwtKeyProperties`/`PemKeyLoader` 의존, `JwkController:3` 이 `RsaPublicKeyRegistry` 의존).

| 대상 | 처분 |
|---|---|
| `JwtFilter`·`JwtTokenVerifier`·`JwtSecurityConfigurer`·`TokenBlacklistLookupPort`·`RedisTokenBlacklistLookupAdapter` | **삭제** |
| `JwtFilterTest`·`JwtTokenVerifierTest`·`RedisTokenBlacklistLookupAdapterTest` | **삭제 또는 Gateway 테스트 벡터로 이관**(#6 golden vector 원천) |
| `JwtKeyProperties`·`PemKeyLoader`·`RsaPublicKeyRegistry`·`JwtAuthProperties` 의 서명/TTL 부분 | **user-service 로 이동**(서명·JWKS 소유자) |
| `TokenClaims`·`TokenParseException` | 검증 전용 → **삭제**(Gateway 는 자체 타입) |
| `TokenHasher` | User 로 이동 **또는** common-auth 잔존 시 "identity-only" 문구 정정 |
| `CommonAuthConfig` 의 JWT 설정 바인딩 | 이동 대상 따라 정리 |
| `LoginUser`·`CurrentUser`·`LoginUserArgumentResolver`·`WebMvcConfig`·`HeaderAuthenticationFilter` | **retain**(servlet identity 변환) |

  - **의존 제거 범위(과잉 삭제 방지)**: User 의 JJWT(서명)·Redis(blacklist/deny **write**) **유지**, Product 의 Redis 캐시(`product-service/build.gradle:29-33`) **유지**. 그 외 서비스에서 *검증 목적으로만* 존재하는 JJWT/Redis 를 제거 — 서비스별 표로 확인.
- [ ] **P15.** 인증 테스트 재작성(**보강 c 로 범위 확정**): SecurityConfig **통합**테스트 2개(`UserSecurityIntegrationTest`·`NotificationSecurityIntegrationTest`) Bearer→X-User-* + `JwtFilterTest`→Gateway reactive 검증 필터 테스트 이관 + `WithMockLoginUserSecurityContextFactory` familyId 반영. 8개 컨트롤러 슬라이스는 SecurityContext 직접 세팅이라 **원칙 무영향**.
- [ ] **P16.** Gateway Dockerfile(단일 `Dockerfile` ARG SERVICE·멀티모듈 COPY·base digest 고정) + CI 이미지 매트릭스 6 + **canonical 목록 갱신(보강 d)**: `scripts/image-contract-lint.sh`·`scripts/promote-images.sh` 에 **도메인 5 + 인프라 gateway 1 분리 정본** 도입(ADR-0010 §5 "5서비스" 의미 보존), image/publish 매트릭스·lint 총계 6/6.
- [ ] **P17.** k8s gateway: base/overlays deployment/svc/cm/secret + **HPA**(SPOF→HA) + Gateway 전용 ServiceAccount + **Gateway ServiceMonitor**(PR4 S9 수집 선결) + `scripts/servicemonitor-selector-lint.sh` 를 도메인 5+인프라 1 분리 정본으로 갱신(보강 d). **외부 노출 단일화(보강 f)**: 5서비스 overlay 의 **NodePort/Internal LoadBalancer patch 제거 → ClusterIP 환원**, 외부 노출은 Gateway 하나. **NetworkPolicy**: 업무 API 는 Gateway pod selector/SA 만 허용 + **monitoring namespace/Prometheus pod 의 `/actuator/prometheus` scrape 예외**. **RS256 키 CSI 마운트는 user-service Pod 전용 — Gateway 미마운트 명시**(보강 a).
- [ ] **P18.** **무중단 롤아웃**(신규, 실행 단위는 위 **PR3a~d** 표): 구서비스는 Bearer 만 읽고(`JwtFilter:31-49`) Gateway 는 Authorization 을 strip 하므로 순서 없이는 **Gateway 선배포=전면 401**, **서비스 선전환=NodePort 경유 `X-User-*` 위조**. 단계 ①Gateway 배포(strip + **Authorization 병행 전달**) → ②트래픽 전환 → ③ClusterIP+NetworkPolicy → ④header-trust rollout(혼재 허용) → ⑤Authorization 중단 + verifier 삭제. **단계별로 실제 값을 적는다(loop2 #2 — "명시할 것"으로 남기지 말 것)**:
  - **진입 조건**: PR3a=Redis/JWKS warm-up + 최초 JWKS 적재 usable key ≥1 + readiness / PR3b=보호·공개·spoof canary 통과 + 오류율 임계 이하 / PR3c=100% 전환 유지 + 혼재 구간 refresh·logout 정상 / PR3d=**family-less 토큰 소멸 증명**(P12 게이트) + 혼재 Pod 0 + rollback window 경과
  - **rollout 파라미터**: `maxUnavailable=0`, 단계별 고유 이미지 태그(단일 태그로 ④/⑤ 구분 불가)
  - **rollback**: 각 단계 역순 명령 + 증적. ⑤ 이후는 **Gateway 의 Authorization 전달 복구가 서비스 롤백보다 선행**해야 함(역순 강제) — 이 제약을 runbook 에 명시


- [ ] **P19.** PR3 테스트: **라우팅**(전 controller prefix 양성 + 오라우팅 음성, `cart` 단수·`admin/products`·JWKS 외부 미노출 포함), **JWT 검증 + 응답 행렬 parameterized**(P12 행렬 전항목: known kid+LKG 정상+alert / unknown kid refresh 성공 후 미존재 401 / unknown kid refresh 실패 503 / cold start usable key 0 → readiness=false / Redis 실패 503 / deny·서명오류·만료 401 / role 부족 403 / 초과 429), RS256 왕복·**fallback off 시 HS512 거부/on 시 허용**·HS256/HS384/none 상시 거부, **family-less 토큰 회귀**(RS256·레거시 HMAC 각각 blacklist hit/miss + Redis 오류 + **보호 API·logout·혼재 Pod** 경로), 헤더 strip+inject(외부 spoof 제거 확증), **공개 경로 무헤더 양성**(SSOT 표 기준: signup/login/refresh·상품 **GET**·webhook) + 동일 서비스 보호 경로 무헤더 **401** + **`POST /api/v1/products/**`·admin API 의 401/403**, **JWKS 기대값 분리**(Gateway 외부 route **404** ↔ User 내부 endpoint **200** — P11 미노출과 정합), **header-trust 음성 매트릭스**(`X-User-Id` 단독·Role 누락·비숫자 ID·임의 Role·blank familyId·중복 헤더 → **401**, 500/anonymous fallback 아님), rate limit 429, **Redis 중단 fault-injection**(deny+RateLimiter 동시 fail-closed→503 + alert), **`WebApplicationType.REACTIVE` 부팅 + servlet/MVC 클래스패스 부재 검증**(보강 e).
  - **conformance = golden vector 방식(loop2 #6)**: 최종 head 에서는 비교 대상(`JwtTokenVerifier`)이 삭제되므로 "기존 verifier 와 동등" 을 최종 CI 에서 실행할 수 없다. → **PR3a 단계에서 differential test**(기존 verifier ↔ Gateway verifier: alg/kid/claims/Redis-key)를 실행하고 **결과 벡터를 독립 test fixture 로 동결**. PR3d 이후 최종 CI 는 **golden vector 에 대한 Gateway 단독 conformance** 만 실행. **k8s 음성·양성**: NodePort/서비스별 LB **부재**, Prometheus target up, non-gateway pod → 업무 API **차단**, Gateway SA → **성공**, payment webhook 의 Gateway 경유. **GKE 필수 exit 실행화(보강)**: enforcement 확인→배포→probe(gateway/non-gateway/monitoring)→artifact 보존→cleanup 을 한 절차의 **보안 smoke 스크립트/수동 승인 job** 으로 만들고 증적 위치 지정. **실행 불가 시 렌더 성공으로 대체 금지 — PR3 미완료 처리.**

### PR4 — 관측성 S9 + HS512 잔재 제거 (D5)

> **범위 축소(보강 g)**: common-auth `JwtFilter`/`JwtTokenVerifier`/blacklist lookup **삭제는 PR3 P14 소관**(ADR-0014 D2-c 가 PR3 exit 로 규정). PR4 는 **Gateway 의 HMAC fallback 종료 + 잔재 sweep** 만 담당한다.

- [ ] **P20.** S9 auth_failure 메트릭: 인증실패(서명오류/만료)·인가실패(403)·reuse 감지·429 counter(+사유 태그). SSOT = Gateway(인증필터/RateLimiter) + User(reuse/logout). 이름 1개소·이동/복제 금지.
- [ ] **P21.** S9 관측성 구현(ADR **본문 수정 금지** — ADR-0009:58 에 S9 행 **이미 존재**, README:8-15 immutable): (a) S9 owner(gateway/user-service) **존재·정합 검증** + ADR-0015:42 per-service lint 정합 + Layer1(`02`/`04`) **현재 상태 동기화**. (b) S9 계약 자체를 바꾸는 경우에만 신규 ADR/Status 절차로 분리. dashboard `$application` 변수 + alert per-service + `observability lint` 갱신(도메인 5+인프라 1 = 6). **B11 sweep**: 식별자 치환은 `application=\?"..."` **및** `service=\?"..."` 형제 라벨 양쪽 + JSON/embedded-YAML escaped/unescaped 양쪽 sweep, 브랜드 문자열(`peekcart-*` uid/tag) 제외.
- [ ] **P22.** **HS512** fallback 제거(overlap 만료 후, dual-validation 종료): **Gateway 검증기 RS256 단일화**(common-auth verifier 는 P14 에서 이미 삭제됨) + `JwtAuthProperties.secret`·HMAC 잔재 sweep. **레거시 `bl:<raw-token>` dual-read 제거**도 여기서 — access token 최대 TTL 경과 증명을 게이트로(보강 a). **ADR-0013 사실 정정(loop2 #8)**: `0013:10,14,29,30` 이 레거시를 "HS256" 으로 기술하나 실제 서명은 **HS512**(512bit secret) — *결정 변경이 아닌 사실 오류*라 README 규칙대로 **`## Update Log` 추가 + `fix(adr):` 커밋**으로 정정(Why 와 구현 사실 분리, 새 ADR 불요). `:65` 의 알고리즘 대안 비교(HS256 vs RS256 vs ES256)는 결정 근거라 **원문 유지**.
- [ ] **P23.** PR4 테스트: 메트릭 counter 통합테스트(사유 태그별), observability lint **negative**(총계 6 불일치·**Gateway ServiceMonitor 누락**·selector 불일치·escaped-quote 잔존 false-green 차단), HS512 제거 후 부팅/검증 회귀(Gateway).

## 4. 영향 파일

- **신규 모듈**: `gateway/**`(build.gradle·routing config·검증 필터·RateLimiter·Dockerfile), `settings.gradle`.
- **PR1**: `user-service/global/jwt/JwtTokenSigner.java`, `peekcart-common-auth/global/jwt/JwtTokenVerifier.java`·`JwtAuthProperties.java`(→`JwtKeyProperties` 신규), User JWKS controller, **테스트 키쌍 = `:common` testFixtures 리소스**(단일 소유, B5).
- **PR2**: `user-service` `refresh_tokens` 마이그레이션(V2)·`RefreshToken.java`·`RefreshTokenRepository(Impl/Jpa)`·`AuthService.java`·`TokenBlacklistPort`/deny 확장·`TokenClaims`(family_id)·common-auth `JwtTokenVerifier`(claim 매핑)·`JwtFilter`/`TokenBlacklistLookupPort`/`RedisTokenBlacklistLookupAdapter`(전환기 family deny enforcement)·`JwtTokenSigner`/`TokenIssuer`(issue 시그니처).
- **PR3**:
  - *신설*: `gateway/**`(routing 정본·reactive 검증 필터·RateLimiter·gateway-local DTO·Dockerfile), `peekcart-common-auth/global/security/`(HeaderAuthenticationFilter·HeaderTrustSecurityConfigurer), `k8s/base/services/gateway/**`(deployment/svc/cm/secret/HPA/SA/**ServiceMonitor**)·NetworkPolicy.
  - *삭제(ADR-0014 D2-c exit)*: common-auth `JwtFilter`·`JwtTokenVerifier`·`JwtSecurityConfigurer`·`TokenBlacklistLookupPort`·`RedisTokenBlacklistLookupAdapter`(+`JwtFilterTest`) 및 common-auth/5서비스의 검증용 JJWT·Redis 의존.
  - *수정*: 5서비스 `*SecurityConfig.java`·`LoginUser`/`LoginUserArgumentResolver`/`AuthController.logout`/`AuthService.logout`·`WithMockLoginUserSecurityContextFactory`·통합테스트 2개·`settings.gradle`·CI images 매트릭스·`scripts/image-contract-lint.sh`·`scripts/promote-images.sh`·`scripts/servicemonitor-selector-lint.sh`·**overlay service patch 10개 제거**(minikube NodePort 5·gke LB 5).
- **PR4**: Gateway/User 메트릭 컴포넌트·grafana dashboard/alert·observability lint·`02`/`04` Layer1 동기화. **ADR 본문은 수정 안 함**(S9 는 ADR-0009:58 기존재) — 존재/owner 정합만 검증, 계약 변경 시에만 신규 ADR.

## 5. 검증 방법

- **PR1**: `./gradlew :user-service:test :peekcart-common-auth:test` — RS256 왕복·alg 거부·JWKS 스키마 그린. HS256 위조 토큰 401.
- **PR2**: `./gradlew :peekcart-common-auth:test :user-service:test` — grace 1회/2회 차단·병렬 1건만 성공, reuse→family revoke→Redis deny write 확증(Testcontainers Redis), common-auth `TokenClaims`/parseToken family_id 회귀.
- **PR3**: `./gradlew build test`(9모듈) — gateway 라우팅(전 prefix 양성 + 오라우팅 음성)·응답 행렬(401/403/429/503+readiness)·fail-closed·헤더 strip/inject·**header-trust 음성 매트릭스(부분·형식오류 401)**·공개 경로 SSOT 표 기준 무헤더 양성 ↔ 보호 경로 401/403·JWKS 404(외부)↔200(내부)·rate limit 429·Redis fault-injection·**conformance golden vector**(PR3a differential → 동결 → PR3d 이후 Gateway 단독)·REACTIVE 부팅+servlet 부재. **단계별 canary**(PR3a~d 진입 조건) 증적. **k8s 음성·양성**(NodePort/LB 부재·Prometheus target up·non-gateway 차단·Gateway SA 성공·webhook Gateway 경유) — minikube CNI 제약 시 **GKE 보안 smoke 스크립트 필수 exit**(enforcement 확인→배포→probe→증적→cleanup, 렌더-only 불충분·미실행 시 PR3 미완료).
- **PR4**: 메트릭 counter 통합테스트(사유 태그)·observability lint negative(총계 6·Gateway ServiceMonitor 누락)·HS512 제거 회귀. 전 모듈 그린.
- **가드**: `assertNoServiceProjectDeps`(gateway↔서비스 직접 의존 금지), **gateway↔`:common` 의존 금지 가드**(보강 e), B1b string-level sweep(route path↔서비스 prefix).

## 6. 완료 조건

- [ ] RS256 서명↔JWKS 검증 왕복, HS256/none/unknown-kid 거부. (PR1)
- [ ] reuse 재제시 → family 전체 무효화 + Redis family deny → 이미 발급된 access token 즉시 차단 확증(PR2 = 전환기 common-auth enforcement, PR3 = Gateway 이관). (PR2/PR3)
- [ ] Gateway 통해서만 인증 통과, 리소스 서비스 direct ingress 거부(헤더 신뢰·NetworkPolicy·**NodePort/LB 환원**). 외부 유입 X-User-* spoof 제거. 공개 경로는 무헤더로도 통과. (PR3)
- [ ] Rate limit route-class별 429 + fail-closed(401/429/503 응답 계약 분리). (PR3)
- [ ] **ADR-0014 D2-c exit**: move/delete/retain 표대로 servlet 검증 컴포넌트 삭제 + 키/서명 클래스 User 이관 + 검증 전용 JJWT·Redis 의존 제거(User 서명·Product 캐시는 유지). (PR3d)
- [ ] **무중단 롤아웃 PR3a~d 완주**(단계별 이미지 태그·진입 조건·`maxUnavailable=0`·역순 rollback runbook, 혼재 구간 인증 무중단). (PR3)
- [ ] **family-less 토큰 소멸 증명 후** 수용 경로 제거 + `LoginUser.familyId` non-null 불변식. (PR3d)
- [ ] header-trust 3-state 계약(anonymous 통과 / 완전 인증 / 그 외 401) — 부분·형식오류가 500·anonymous 로 새지 않음. (PR3)
- [ ] **GKE 보안 smoke 증적** 확보(NetworkPolicy 양성·음성·scrape). 미실행 시 PR3 미완료. (PR3)
- [ ] S9 auth_failure 메트릭 + ADR-0009 S9 행 + observability lint 6/6(도메인 5+인프라 1). (PR4)
- [ ] HS512 fallback + 레거시 `bl:` dual-read 제거 후 9모듈 그린, 인증 회귀 0. (PR4)
- [ ] 보안 묶음 L-001/002/003/019 종결, ADR-0013 구현 완료.

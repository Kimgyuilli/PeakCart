# task-impl3-pr3d-internal-token — 구현 ③ PR3d 재정의: Gateway 서명 내부 토큰

> 선행 **ADR-0017(Accepted, 경로 A)**. ADR-0014 D2-c(servlet 검증 exit) 성립 조건 유지. 상위 계획 `task-impl3-spring-cloud-gateway.md` 의 **PR3d 를 재정의**한다(평문 header-trust 굳히기 → 서명 assertion 격상).
> 상위 계획의 PR3d 행(§PR3 실행 분할)·P14 클래스 처분표(loop2 #3)는 본 문서(P1~P10)가 정본이다.
> **Codex plan 리뷰 loop1 반영(11건 P0:1/P1:8/P2:2 전량)** — 핵심: 롤아웃 순서 P0, gateway 키저장 lint 충돌, 키 도메인 혼선, 검증 계약 미고정, 키 소유 lint, B1 누락 인바운드.

## 1. 목표

- **평문 header-trust → 서명 assertion**: Gateway 가 `X-User-*`(평문) 대신 자기 개인키로 서명한 짧은 수명 내부 JWT(`X-Internal-Auth`)를 주입. 리소스 서비스는 Gateway 공개키로 서명·iss·kid·exp 검증 후 claims 에서 신원 추출(ADR-0017 D1/D3).
- **신뢰 경계 이중화**: NetworkPolicy(네트워크) AND 서명(암호) — 둘 중 하나 실패로 신원 위조 불가(ADR-0017 D4).
- **verifier 용도 변경**: `PemKeyLoader`/JWT 파서를 삭제하지 않고 내부 토큰 검증기로 전환. 삭제 대상은 **사용자 토큰 검증 필터 + 서비스 측 blacklist lookup**(ADR-0017 D5).
- **성공 기준**: 9모듈 그린 / **signed-only 상태에서** 평문 `X-User-*` 직접 주입 무시·위조 `X-Internal-Auth` 401(스푸핑 표면 0, review #2 — dual-accept 구간이 아니라 최종 상태에서 실증) / gateway 서명 토큰만 인증 통과 / 사용자 access token 을 `X-Internal-Auth` 로 제시하면 iss/kid 핀으로 401 / 만료·future-iat·과수명·missing-claim 거부 / 공개 경로 무헤더 통과 / **GKE preflight(enforcement 실증) + signed-only crypto barrier 2단 통과**.

## 2. 배경 / 제약

### 현재 코드 (grep 검증 완료, 2026-07-25)

- **주입(Gateway)**: `GatewayAuthenticationFilter.withTrustedHeaders()`(`gateway/.../GatewayAuthenticationFilter.java:160`, **static** — :100-108,159-176 에서 `authenticate` 가 static 호출) — 검증된 `GatewayClaims`(`userId/role/familyId/expiration`)로 평문 3개 set. 외부 유입은 `stripTrusted`(:153)·재주입 전 remove(:163)로 strip. `TRUSTED_HEADERS`(:54)=이 3개. 생성자(:59-69)는 verifier/denyLookup/publicRules 만 주입.
- **소비(서비스)**: `HeaderAuthenticationFilter`(`peekcart-common-auth/.../security/HeaderAuthenticationFilter.java:44`) — 평문 3-state, **서명 검증 없음**(:24 주석). `HeaderTrustSecurityConfigurer` 가 5서비스 SecurityConfig 배선.
- **재활용 자산**: `RsaPublicKeyRegistry.find(kid)`/`all()`(:26-45)·`JwtKeyProperties`(`app.jwt.rs256.public-keys`, :22)·`PemKeyLoader`. **주의(review #3)**: `RsaPublicKeyRegistry.all()` → `JwkController`(`user-service/.../presentation/JwkController.java:26-34`)가 JWKS 로 전량 게시. → gateway 키를 이 레지스트리에 넣으면 User JWKS 로 노출.
- **Gateway 서명 부재**: gateway 는 `GatewayJwtVerifier`(검증)만 보유, **개인키·서명기 없음**. → 신규(P1).
- **키 저장 계약(review #1)**: ADR-0013 D2 = **GCP Secret Manager PEM + CSI/파일 마운트, k8s Secret·환경변수 금지**. `scripts/gateway-exposure-lint.sh:211-225` 는 gateway 의 Secret volume/projected Secret 을 **무조건 거부** → gateway `secretKeyRef`/`secret:` volume 신설 불가.

### B1 — 역의존 스윕 (평문 소비 → 서명 소비로 대체하는 seam)

| 인바운드 간선 | 현재 | 처분 |
|---|---|---|
| `HeaderAuthenticationFilter`(평문 3개 헤더 신뢰) | 서명 없이 헤더 신뢰 | **재작성(P4)**: `X-Internal-Auth` 검증 → claims 로 인증. 3-state 의미 보존 |
| `GatewayAuthenticationFilter.withTrustedHeaders`(**static**, 평문 3개 주입) | 평문 주입 | **재작성(P2)**: `X-Internal-Auth` 단일 주입 + 외부 유입 `X-Internal-Auth`·`X-User-*` strip |
| **`GatewayAuthenticationFilter` 생성자/bean graph**(review #6) | verifier/denyLookup/publicRules 만 주입 | **변경(P2)**: `InternalTokenIssuer` 생성자 주입 → static `withTrustedHeaders` 를 인스턴스 메서드화(또는 issuer 명시 인자). 컴파일 인바운드 |
| **`authenticate` → `withTrustedHeaders` → `issuer.issue`**(review #6) | static 체인 | **재배선(P2)**: issuer 호출 지점 확정 |
| **`InternalTokenProperties`/`InternalGatewayPublicKeyRegistry` 등록**(review #6) | 없음 | **신설(P1/P3)**: `@ConfigurationProperties` scan/`@EnableConfigurationProperties`, gateway 개인키 **startup load fail-fast** |
| **`JwkController` → `RsaPublicKeyRegistry.all()`**(review #3/#6) | User JWKS 가 레지스트리 전량 게시 | **격리(P3/P5)**: gateway 키를 이 레지스트리에 **넣지 않음** — 별도 `InternalGatewayPublicKeyRegistry` |
| `HeaderAuthenticationFilterTest`(3-state 13종) | 평문 헤더 케이스 | **재작성(P9)**: 서명 유효/만료/wrong-iss/wrong-kid/bad-sig/malformed/missing-claim |
| **`GatewayAuthenticationFilterTest`**(review #7) | 목록 누락 | **추가(P9)**: strip/평문3종 제거/외부 X-Internal-Auth 완전교체/중복헤더/RateLimiter attr 유지/Authorization 미전달/signer fail-closed |
| `UserSecurityIntegrationTest`·`NotificationSecurityIntegrationTest` | `X-User-*` 로 인증 | **재작성(P9)**: gateway 서명 토큰(fixture) |
| **Product/Order/Payment 통합테스트**(review #7) | 목록 누락(5서비스인데 2개만) | **추가(P9)**: context-boot + 보호/공개 경로 보안 통합 |
| `LoginUser`/resolver(`userId/role/familyId`) | 헤더 details 매핑 | **불변**: 계약 동일, 소스만 헤더→claims |
| `RsaPublicKeyRegistry`/`PemKeyLoader`/`JwtKeyProperties` | 잔존(rollback) | **분리**: User access-token 검증/JWKS 전용 유지 — gateway 내부키는 별도 레지스트리 |
| `JwtFilter`·`JwtTokenVerifier`·`TokenBlacklistLookupPort`·`RedisTokenBlacklistLookupAdapter`(서비스 측) | 사용자 토큰 검증·blacklist | **삭제(P6)**(ADR-0014 D2-c) — deny 는 Gateway 소유 |

### B2 — ADR 타깃 ≠ 현재 코드 (없는 것은 "만든다")

- **Gateway 서명 키페어·서명기**: 없음 → P1. **내부 토큰 검증기 + 전용 공개키 레지스트리**: 없음 → P3. **gateway 개인키 CSI 마운트**(k8s Secret 아님): 없음 → P5. **키 소유 경계 lint**: 없음 → P7. **키 회전 절차**: 없음 → P8.

### B3 — 롤아웃 상호작용 (PR3c GKE 증적 미확보와의 결합, review #2)

PR3c 는 GKE 실 클러스터 증적 미확보 = **평문 header-trust 가 아직 영구 배포되지 않음**. **경로 A** 지만, dual-accept(평문 수용) 구간을 **NetworkPolicy enforcement 실증보다 뒤에 두면 안 된다** — 그 사이 직접경로가 열려 있으면 평문 스푸핑 창이 생긴다. → §7 안전순서: **enforcement 선(先)실증 hard gate → dual-accept → 서명배포 → signed-only → 재실증**. "평문 무시" 기대는 **signed-only 최종 상태에서만** 검증한다(dual-accept 구간은 정의상 평문 수용).

### 트레이드오프

- 서비스당 요청당 RS256 검증 1회(sub-ms, Redis 조회 없음). clock skew → exp 초 단위 + leeway ≤5s(NTP 전제).
- 계약 이중 지점(발행 Gateway ↔ 검증 common-auth) → **주입 Clock 교차모듈 conformance**(P9, review #8)로 고정.
- Gateway 서명이 Reactor `map`(Netty 이벤트루프)에서 동기 실행(review #11) → p95/p99 측정·예산·초과 시 격리(P2).
- Gateway 개인키 = 신규 관리 대상이나 Gateway 는 이미 인증 SPOF → 새 SPOF 아님. 키 회전 절차 필요(P8).

## 3. 작업 항목

> P1~P10. 상위 계획 P14(삭제분)·P18 ⑤ 를 본 항목으로 대체.

### 발행 (Gateway)

- [ ] **P1.** `gateway/auth` 에 `InternalTokenIssuer` + `InternalTokenProperties` 신설.
  - `InternalTokenProperties`(`@ConfigurationProperties("app.gateway.internal-token")`, `@EnableConfigurationProperties` 등록) = `activeKid`·`privateKeyLocation`(PKCS#8 PEM, CSI 마운트 파일)·`ttlSeconds`(기본 30, 상한 검증)·`skewSeconds`(0~5). **부팅 시 fail-fast validation**(review #4/#6): 개인키 startup load 실패·activeKid 불일치·ttl 범위 위반 시 context 기동 거부.
  - **issuer/claim 이름 = 단일 출처 계약(loop2 #6 + loop3 #7)** — 설정 가능 값 금지. 단, 발행(gateway)·검증(common-auth)이 **각 모듈에 리터럴을 따로 두면 그 자체가 drift 원천**이다. gateway 는 common-auth(servlet)를 의존할 수 없으므로(B6) → **servlet/reactor 비의존의 작은 `internal-token-contract` 모듈**에 issuer(`peekcart-gateway`)·claim 이름(`sub/role/fid`)을 **한 번만 정의**하고 양 모듈이 컴파일 의존으로 참조한다(별도 모듈이 과하면 최소한 생성 소스+컴파일 의존으로 단일 출처 강제). §4 참조.
  - `InternalTokenIssuer.issue(GatewayClaims) -> String`: jjwt 서명(**alg=RS256 고정**, header kid=activeKid), claims `sub`=userId·`role`·`fid`·`iss`·`iat`·`exp`=now+ttl. `fid` 필수/선택 정책은 P6 게이트를 따른다(review #10). PEM 로더는 gateway-local(`:common` servlet 미의존, B6).
- [ ] **P2.** `GatewayAuthenticationFilter` 재작성:
  - **생성자에 `InternalTokenIssuer` 주입**(review #6) → static `withTrustedHeaders`(:160)를 인스턴스 메서드화. `authenticate`(:100-108) 체인에서 issuer 호출.
  - 평문 3개 set 제거 → `headers.set("X-Internal-Auth", issuer.issue(claims))`. `TRUSTED_HEADERS`(:54)에 `X-Internal-Auth` 추가(외부 유입 strip 대상, 공개 경로 `stripTrusted` 포함). `AUTHENTICATED_USER_ID_ATTR`(:52) 유지.
  - **WebFlux 예산 = 2단계 분리(loop3 #8)**: **(a) 사전 baseline·예산 확정**(코드 변경 前) — 고정 환경에서 기존 요청 p95/p99·event-loop lag 를 측정해 **허용 절대값 또는 baseline 대비 최대 증가율 + scheduler thread/queue·포화 기준을 계획에 커밋**(측정 후 기준을 결과에 맞춰 이동하는 것 방지). **(b) 구현·검증** — 부하 조건(동시성·RSA 키 크기 2048/3072) 하 signing/전체 p95/p99·event-loop 지연이 (a) 예산 이내. 초과 시 `Mono.fromCallable` + 서명 전용 bounded scheduler(포화 시 503 fail-closed + 테스트).

### 검증 (common-auth / 리소스 서비스)

- [ ] **P3.** `peekcart-common-auth/.../security` 에 `InternalTokenVerifier` + **전용 `InternalGatewayPublicKeyRegistry`**(review #3) 신설:
  - **키 도메인 분리**: gateway 공개키는 `app.internal-token.public-keys`(kid→PEM)에 바인딩된 **별도 레지스트리**로 로드 — User 의 `app.jwt.rs256.public-keys`/`RsaPublicKeyRegistry`(JWKS 소유)에 **넣지 않는다**. 서비스 검증기에만 주입. **내부 레지스트리 fail-fast(loop2 #5)**: 빈 키셋·잘못된 PEM·activeKid/허용 kid 불일치 시 서비스 **startup 실패**(정상 배선만 격리하는 게 아니라 오배선을 부팅에서 잡는다).
  - **검증 계약 = 전환기/최종 2모드 명시(review #4·loop2 #7)**: 공통 = `alg==RS256`(명시), 필수 `kid/iss/sub/role/iat/exp`, `exp>iat`, `exp-iat<=maxInternalTtl`, `iat<=now+skew`(skew 0~5s), `iss==peekcart-gateway`(코드 상수), kid=gateway 허용 집합만, claim 타입·허용 role(USER/ADMIN) 검증, HS fallback 불허. **전환기 모드**: `fid` optional. **최종(signed-only) 모드**: `fid` **필수** — missing/blank/wrong-type fid **거부**. 모드는 명시 설정으로 전환하며 최종 기본값은 최종 모드(P6 게이트). 반환=`LoginUser`.
- [ ] **P4.** `HeaderAuthenticationFilter`(:44) 재작성 → 내부 토큰 필터(이름 유지 or `InternalTokenAuthenticationFilter`). 3-state 보존:
  - `X-Internal-Auth` **부재** → anonymous 로 체인 계속(공개 경로 보존)
  - **유효 서명**(P3 계약 통과) → `LoginUser` 인증 세팅(principal=userId, authority=`ROLE_<role>`, details=familyId)
  - **무효**(서명오류·만료·wrong-iss/kid·과수명·future-iat·missing-claim·malformed) → 401(entrypoint, 500·anonymous fallback 금지)
  - `HeaderTrustSecurityConfigurer` 는 필터만 교체(공통 정책 csrf/STATELESS/entryPoint/accessDeniedHandler/MdcFilter 순서 전부 보존 — PR3c Codex diff #6 계약).

### 키 배포 / config / k8s

- [ ] **P5.** 키 배포(review #1/#3):
  - Gateway 개인키 = **GCP Secret Manager Gateway 전용 secret + Secrets Store CSI read-only 파일 마운트**(ADR-0013 D2 정합). **k8s Secret(`secretKeyRef`/`secret:` volume)·환경변수 금지**. 로컬/테스트는 testFixtures 키쌍(산출물 비포함, PR1 선례). `application-k8s.yml`(gateway) 마운트 경로.
  - 5서비스 = gateway 공개키 kid 를 **`app.internal-token.public-keys`**(non-secret → ConfigMap/베이크)에 추가. **`app.jwt.rs256.public-keys` 에는 넣지 않는다**(JWKS 노출 방지).
  - **RS256 개인키 마운트 경계**: gateway 개인키=gateway Pod 전용, user access-token 개인키=user-service 전용, 나머지 서비스=공개키만(P7 lint 로 강제).

### 삭제 / 계약 확정 (ADR-0014 D2-c exit)

- [ ] **P6.** 서비스 측 사용자 토큰 검증 잔재 삭제 + Gateway Authorization 전달 중단:

| 대상 | 처분 |
|---|---|
| `JwtFilter`·`JwtTokenVerifier`(서비스 사용자 토큰 검증)·`TokenBlacklistLookupPort`·`RedisTokenBlacklistLookupAdapter`(서비스 측) | **삭제**(deny=Gateway 소유) |
| `RsaPublicKeyRegistry`·`PemKeyLoader`·`JwtKeyProperties`(공개키) | **retain**(User JWKS 전용 유지). 내부 토큰은 별도 `InternalGatewayPublicKeyRegistry`(P3) |
| `JwtKeyProperties`(privateKey)·서명·`JwkController` | **user-service 소유 확정** |
| `TokenClaims`·`TokenParseException`(사용자 토큰 파싱) | **삭제**(내부 토큰은 `InternalTokenVerifier` 자체 타입) |
| `LoginUser`·`CurrentUser`·`LoginUserArgumentResolver`·`WebMvcConfig` | **retain** |

  - **⚠️ verifier 비활성화 시점(loop2 #1 — 핵심)**: 서비스의 사용자 토큰 verifier(`JwtFilter`)는 **§7 ④ signed-only 전환과 동시에 비활성화**한다(코드 물리 삭제는 후속 정리로 미뤄도, **활성 상태로 두면 안 된다**). ④~⑤ 구간에 `JwtFilter` 가 살아 있으면 서비스가 직접경로 `Authorization: Bearer <유효 사용자토큰>` 을 그대로 인증 → 내부 서명 없이 Gateway 우회가 가능한데도 barrier 가 통과한다. 따라서 signed-only 이미지는 **내부 토큰 필터만** 활성.
  - Gateway: `GatewayAuthenticationFilter` 의 Authorization 전달 중단(PR3a 전환기 주석 :37-38 해제) — 다운스트림엔 `X-Internal-Auth` 만. (전달 중단·코드 물리 삭제 = §7 ⑥ 후속 정리.)
  - **familyId 계약 확정(review #10·loop2 #7)**: family-less 소멸 게이트(마지막 family-less 토큰 발급 + access token 최대 TTL 경과 증명) 통과 시 → verifier **최종 모드**(P3) 전환 = issuer·verifier **모두 `fid` 필수** + `LoginUser.familyId` non-null 불변식 + **missing-fid 거부 테스트**. 전환기 지원이 실제 필요하면 허용기간·feature flag·제거 배포 단계를 명시하고 **최종 기본값 = 최종 모드(deny)** 고정.

### 검증 도구 / 운영

- [ ] **P7.** 키 소유 경계 lint(review #1/#5·loop2 #4/#5):
  - `scripts/gateway-exposure-lint.sh` 를 **"gateway 비밀 0" → "승인된 Gateway CSI 마운트 정확히 1개"** 로 변경(k8s Secret/`secretKeyRef`/projected Secret 금지 유지).
  - **CSI exact allow-list(loop2 #4 — false-green 차단)**: "승인 provider" 까지만 보지 말고 **CSI driver·SecretProviderClass 이름/namespace/provider·GCP secret resource ID·파일 alias·volumeMount 경로·`readOnly`** 를 하나의 exact allow-list 로 묶어 **정확히 1개** 강제. self-test = **CSI 0개·2개·wrong SPC·wrong secret object·wrong mountPath·`readOnly` 누락(writable)** 을 **진단 문자열까지 대조**(non-zero 여부만 보면 다른 위반에 걸려도 통과).
  - **신규 key-ownership lint**: 렌더된 **모든 Pod-producing workload**(Deployment/**StatefulSet/DaemonSet/ReplicaSet**/Job/CronJob/Pod·initContainer/sidecar 포함, loop2 #4) 전수 — gateway 개인키=gateway 승인 CSI 만, user 개인키=user-service 만, 나머지 컨테이너엔 두 개인키 모두 부재. 오마운트·env·k8s Secret·projected Secret·미승인 CSI provider·전 workload 종류 음성 self-test.
  - **property-ownership lint(loop2 #5)**: 5서비스 최종 config 에서 **gateway kid 가 `app.jwt.rs256.public-keys` 에 없음**(내부키는 `app.internal-token.public-keys` 에만) 을 렌더/config 로 강제. User JWKS 검증은 kid 뿐 아니라 **공개키 fingerprint** 로 gateway 키 부재 확인(같은 키를 다른 kid 로 넣는 우회 차단).
- [ ] **P8.** 키 회전 절차(review #9, ADR-0017 D2): runbook + 작업항목 — ① 모든 서비스에 new 공개키/허용 kid **선배포** → ② 전체 적용 확인 → ③ Gateway activeKid/개인키 전환 → ④ old 토큰 TTL+skew 경과 → ⑤ old kid 제거. active/previous 동시 검증·순서 역전 실패·롤백을 통합/GKE smoke 로 검증.

### 테스트 / GKE

- [ ] **P9.** 단위/통합/conformance:
  - `InternalTokenIssuerTest`: claims 왕복·kid 헤더·alg=RS256·fid 정책(P6 게이트).
  - `InternalTokenVerifierTest`(**음성 매트릭스, review #4**): 유효→LoginUser / 만료 / missing-exp / missing-iat / future-iat / 과수명(exp-iat>maxTTL) / wrong-iss(사용자 access token) / wrong-kid / bad-sig / HS 서명 / malformed / 잘못된 claim 타입·role → 전부 거부.
  - `GatewayAuthenticationFilterTest`(review #7): strip/평문3종 제거/외부 X-Internal-Auth 완전교체/중복헤더/RateLimiter attr 유지/(P6 후)Authorization 미전달/signer 실패 fail-closed.
  - **JWKS 배제 테스트(review #3·loop2 #5)**: User JWKS 응답에 gateway kid **부재** — kid 뿐 아니라 **공개키 fingerprint** 로 gateway 키가 안 실렸음을 검증(같은 키 다른 kid 우회 차단) + 내부 검증기가 User kid/access token **거부**.
  - **직접경로 Bearer 거부 테스트(loop2 #1)**: signed-only 서비스가 직접경로 `Authorization: Bearer <유효 사용자토큰>` 을 **거부**(JwtFilter 비활성 확인) — 내부 서명 없는 Gateway 우회 차단.
  - 통합 재작성 + **5서비스 전체**(User/Product/Order/Payment/Notification) context-boot·보호/공개 경로 보안.
  - **스푸핑 회귀(signed-only 상태, review #2)**: 평문 `X-User-*` 직접 주입 → 무시 / 임의 서명 `X-Internal-Auth` → 401 / 만료 → 401.
  - **교차모듈 conformance(review #8)**: 주입 Clock 으로 실제 `InternalTokenIssuer` 출력 ↔ 실제 `InternalTokenVerifier` 소비. fid 유무·active/previous kid·TTL/skew 경계·wrong alg/iss/kid·PEM 파싱 양·음성. 버전된 테스트 계약+키 fixture(servlet 런타임 의존 없이 공유).
- [ ] **P10.** GKE 보안 smoke **2단 분리**(review #2, P37 `gke-security-smoke.sh` 확장):
  - **① network preflight barrier(hard gate, dual-accept 前)**: enforcement 활성(Dataplane V2 OR networkPolicy.enabled)·non-gateway 직접경로 차단·gateway 공개 200·Prometheus up. **불가 시 rollout 중단**.
  - **② signed-only crypto barrier(전환 後)**: 위조 `X-Internal-Auth`(임의 개인키) → 401 / 평문 `X-User-*` 직접 주입 → 무시 / **직접경로 `Authorization: Bearer <유효 사용자토큰>` → 거부(loop2 #1)** / 정상 서명 → 200. **실행 불가 시 렌더 성공으로 대체 금지 — PR3d 미완료 처리**.

## 4. 영향 파일

- **internal-token-contract**(신규 최소 모듈, loop3 #7): servlet/reactor 비의존 — issuer 상수·claim 이름 단일 정의. gateway·common-auth 양쪽이 컴파일 의존. `settings.gradle` include.
- **gateway**: `auth/InternalTokenIssuer.java`·`auth/InternalTokenProperties.java`(신규)·`auth/GatewayAuthenticationFilter.java`(생성자+주입/strip/Authorization 중단)·`config/`(properties enable)·`build.gradle`(jjwt 서명·:internal-token-contract)·`application-k8s.yml`(CSI 마운트 경로)·testFixtures 키쌍.
- **common-auth**: `security/InternalTokenVerifier.java`·`security/InternalGatewayPublicKeyRegistry.java`·`security/InternalTokenProperties.java`(신규)·`security/HeaderAuthenticationFilter.java`(재작성)·`security/HeaderTrustSecurityConfigurer.java`(필터 배선)·`jwt/JwtFilter|JwtTokenVerifier` 삭제·`auth/TokenBlacklistLookupPort|RedisTokenBlacklistLookupAdapter` 삭제·`jwt/TokenClaims|TokenParseException` 삭제.
- **user-service**: 서명·JWKS·`JwtKeyProperties`(private)·`RsaPublicKeyRegistry`·`JwkController` 소유 확정(내부키 미포함 확인).
- **5서비스 config**: `app.internal-token.public-keys`(gateway kid)·`app.internal-token.{issuer,gateway-kids}`. `app.jwt.rs256.public-keys` 에는 gateway kid 미추가.
- **k8s**: `k8s/base/services/gateway/`(SecretProviderClass/CSI volume·read-only 마운트)·overlays(gateway CSI patch). **k8s Secret 미생성**.
- **scripts**: `gateway-exposure-lint.sh`(0→1 CSI) + 신규 key-ownership lint(+CI policy step·self-test).
- **테스트**: §3 P9 목록 전체.

## 5. 검증 방법

- 9모듈 `./gradlew build` 그린 + gateway `docker build` + health smoke.
- P9 전 항목 그린(특히 검증 음성 매트릭스·GatewayAuthenticationFilterTest·JWKS fingerprint 배제·**직접경로 Bearer 거부**·5서비스 통합·교차모듈 conformance·스푸핑 회귀 3종).
- CI lint: image-contract 6/6·networkpolicy-contract·**gateway-exposure(CSI exact allow-list 정확히 1개)**·**key-ownership(전 workload 종류)**·**property-ownership(gateway kid ∉ app.jwt.rs256.public-keys)** self-test 그린(0/2/wrong SPC/object/path/writable 진단 대조). 렌더 양성/음성(gateway 개인키 오마운트 → lint fail).
- WebFlux 서명 **수치 예산**(고정 부하·키크기·환경 하에 signing·전체 p95/p99·event-loop 지연 상한) 이내(초과 시 bounded scheduler thread/queue 상한·포화 503 격리 반영).

## 6. 완료 조건

- 위 §5 전부.
- **P10 ① network preflight + ② signed-only crypto barrier 실 클러스터 증적**(위조 401·평문 무시 포함) — **렌더/lint 성공을 canary 통과로 기록 금지**.
- 키 회전(P8) runbook active/previous overlap 검증 1회.
- Layer1(`02`/`04 §10-2`) 동기화(서명 assertion·키 도메인 분리 반영) · ADR-0014 D2-c exit 성립 확인. (ADR-0017 채택 2026-07-25 Accepted — 경로 A. 키저장 CSI 명세는 ADR-0017 Update Log 참조.)

## 7. 롤아웃 (경로 A — enforcement 선실증 · review #2 재배열)

> 안전순서: **NetworkPolicy enforcement 를 dual-accept 보다 먼저 실증**한다. dual-accept 구간은 평문을 수용하므로, 그 사이 직접경로가 열려 있으면 스푸핑 창이 된다.

> **단계 간 수렴 hard gate(loop2 #2)**: `maxUnavailable=0` 은 가용성만 보장할 뿐 **전량 수렴을 보장하지 않는다**. 구 gateway 가 평문을 보내는 동안 일부 서비스만 signed-only 로 넘어가면 인증 실패가 난다. → 각 배포 단계는 **전량 수렴 관측**을 다음 단계 진입 hard gate 로 둔다.

1. **① GKE preflight hard gate(P10 ①)**: NetworkPolicy/CNI enforcement 활성 + non-gateway 직접경로 차단을 **실증**. 불가 시 rollout **중단**(진입 조건 미충족).
2. **② 서비스 dual-accept 배포**(`maxUnavailable=0`): gateway 공개키(`app.internal-token.public-keys`)·검증 설정 포함. `X-Internal-Auth`(유효 서명) **또는** 평문 `X-User-*` 수용. → **gate: 전체 서비스 dual-accept 수렴 + 양방식(서명·평문) smoke** 후 ③.
3. **③ Gateway 서명 주입 배포**: 평문 주입 중단, `X-Internal-Auth` 주입. Authorization 전달 유지(rollback 창). → **gate: 전체 Gateway 서명 주입 수렴 + 평문 주입 0 관측** 후 ④.
4. **④ 서비스 signed-only 전환 배포**: 평문 수용 제거 → `X-Internal-Auth` 만 수용 + **사용자 토큰 verifier(`JwtFilter`) 동시 비활성화(loop2 #1)**. → **gate: 전체 서비스 signed-only 수렴** 후 ⑤.
5. **⑤ signed-only crypto barrier 재실증(P10 ②)**: 위조 `X-Internal-Auth` 401 · 평문 직접주입 무시 · **직접경로 Bearer 거부** · 정상 200. + canary.
6. **⑥ 후속 정리**: Gateway Authorization 전달 중단(P6) + 서비스 verifier **코드 물리 삭제**(활성 비활성화는 ④ 에서 이미 완료).
- **rollback 순서(loop2 #3 — 역순 원칙 정정)**: `① 서비스 verifier 재활성(복구) → ② Gateway Authorization 전달 복구 → ③ 서비스 dual-accept 복구 → ④ Gateway 평문 주입 복구`. (Gateway 평문을 먼저 복구하면 signed-only 서비스가 거부하고, Authorization 을 먼저 복구해도 verifier 가 비활성이면 소비 못 한다 — verifier 복구가 최선두.) 각 단계 고유 이미지 태그.

## 8. 구현 노트 (/work 지침 — loop3 #1~#6, 재리뷰 대상 아님)

> plan 이 §3/§7 에서 **원칙**으로 명령한 검증기·barrier·불변식의 **구현 방법**. altitude 상 plan 본문이 아니라 /work 에서 이 지침대로 구현한다(계속 plan 에 적어 넣으면 검증기-후퇴 무한루프). 각 항목은 해당 P-item 구현 시 충족 기준이다.

- **[§7 수렴 gate → 판정식 (loop3 #1)]** "전량 수렴"·"평문 주입 0" 을 관측 가능식으로: workload 별 `observedGeneration==metadata.generation`, `updated/ready/availableReplicas==desired`, `unavailable==0`, 구 ReplicaSet `replicas==0`, 기대 image digest/config hash 일치. "평문 주입 0" 은 무트래픽에서 참이 되므로 → **Gateway 각 Pod 를 개별 synthetic 요청**해 downstream 에서 `X-Internal-Auth` 존재 + `X-User-*` 부재 확인, timeout 시 중단.
- **[P3/P4 부팅 불변식 (loop3 #2)]** 모드=typed enum + 조합 검증: `SIGNED_ONLY` 이면 `fidRequired=true` **AND** `JwtFilter` bean 수=0 **AND** 내부 필터 bean 수=1 아니면 **startup 실패**. issuer 는 null/blank fid **발행 fail-closed**. 실제 모드·필터 집합을 readiness/metric 으로 노출(barrier 가 Pod 별 확인).
- **[§7 rollback 정합 (loop3 #3)]** verifier·내부토큰 필터가 **함께 존재하는 rollback 전용 호환 이미지**를 고정 + 두 필터 순서·principal 일치·충돌 시 fail-closed 테스트. ③/④/⑤/⑥ 각 출발 단계별 rollback 행렬 + 단계별 전량 수렴·smoke gate.
- **[P7 CSI 계수 단위 (loop3 #4)]** "정확히 1개" = **SPC object 1 ↔ CSI volume 1 ↔ gateway container volumeMount 1** 관계로 계수(volume 을 여러 경로/컨테이너 mount 금지), 양쪽 `readOnly=true`. SPC `parameters.secrets` entry 정확히 1개, `secretObjects`(k8s Secret 동기화)·`nodePublishSecretRef` **금지**. 각 우회별 음성 self-test + 고유 진단 코드.
- **[P7 property-ownership 산출 (loop3 #5)]** 렌더 ConfigMap 만 보지 말고 **5서비스를 실제 k8s profile·배포 env/args 로 부팅**해 Spring `Environment` 최종 바인딩된 두 key map 검사(profile override·`SPRING_APPLICATION_JSON`·relaxed binding·configtree 경로 포함). fingerprint = PEM 문자열 hash 금지 → **SPKI DER SHA-256 정규화** 비교(동일 키 재인코딩·다른 kid 우회 차단). self-test 에 override·다른 kid·PEM 재포맷 변이 포함.
- **[P7 lint 견고성 (loop3 #6)]** `--self-test`/CI 에서 **kubectl 부재 → exit 2**(로컬 skip 은 명시 옵션만). 진단 대조는 부분 문자열 grep 금지 → **안정적 고유 ID + 기대 발생 횟수** 검증(다른 진단이 같은 문구 포함 시 false-green 차단).

# task-impl3-pr3d-internal-token — audit log

> `/plan` · `/work` 게이트 이력. 결정과 그 근거만 남긴다(상세 산출물은 계획서 §9 / progress 문서).

## 2026-08-12 — GW-1 (branch)

- 자동 통과: `feat/impl3-pr3d-a-internal-token` (사용자 선호 — 계획 승인 생략)
- 분할 확정: 계획서 §9 (PR3d-a 코드 / PR3d-b 키배포·클러스터). 근거는 §9.1 코드 검증 3건.

## 2026-08-12 — GW-2 (work loop 1, PR3d-a diff · split 3 chunk)

- 리뷰 run:
  - `work:20260811T192347Z:217f571c-74f4-4811-b3e0-1e801aa372a6:1:c1` (실행코드 31파일/1848줄) — 10건 (P0:4, P1:4, P2:2)
  - `work:20260811T192347Z:217f571c-74f4-4811-b3e0-1e801aa372a6:1:c2` (테스트 21파일/1888줄) — 5건 (P1:4, P2:1)
  - `work:20260811T192347Z:217f571c-74f4-4811-b3e0-1e801aa372a6:1:c3` (설정·스크립트·문서 16파일/1830줄) — 9건 (P1:4, P2:5)
- 총 24건. **분할 아티팩트 10건 기각 · 실제 결함 7건(중복 제거) 전량 반영.**

### 기각 — chunk 분할 아티팩트 (10건)

diff 를 3 chunk 로 나눠 각각 독립 리뷰했더니, **다른 chunk 에 있는 파일을 "패치에 없다"** 고 판정한 지적이
10건 나왔다(c1 P0 1~4·P1 5·P1 6, c2 P1 4, c3 P1 3·P1 4·P2 5·P2 9). 예: c1 은 `InternalTokenVerifier` 부재로
"컴파일 불가 P0" 를 냈지만 그 파일은 c2 에 있다. **full build 그린(567 테스트·가드 5종)이 반증**이며,
파일별 chunk 소속을 대조해 확인했다. → split 리뷰에서 "누락" 계열 지적은 저장소 상태로 교차검증한 뒤 판정한다.

### 반영 — 실제 결함 (7건)

| # | 출처 | 결함 | 반영 |
|---|---|---|---|
| 1 | c1:7 · c2:2 · c3:1 (P1) | 직접경로 Bearer 거부 테스트가 `"any.user.access.token"` (깨진 문자열) — 사용자 토큰 verifier 가 되살아나도 원래 401 이라 **vacuous-negative** | `TestRsaKeys.mintUserAccessToken()` 신설(유효 RS256·미만료) + 4서비스에 `TestRsaKeys.register()` 로 **검증키를 일부러 등록** → verifier 가 재배선되면 테스트가 실제로 깨진다 |
| 2 | c2:1 (P1) | `InternalTokenModeInvariant` 가 SecurityFilterChain 0개일 때 조기 return → **fail-open** (보안설정 미배선 컨텍스트가 조용히 기동) | 0개도 위반으로 처리. 슬라이스는 `@Component` 미스캔이라 영향 없음 |
| 3 | c2:3 (P1) | `keyDomainsAreSeparated` 가 고정 fixture 키 1개만 대조 → 내부 레지스트리에 *다른* 키가 바인딩되고 그 키가 User 쪽에도 있으면 통과 | 두 레지스트리 **fingerprint 집합 전체의 서로소** 검사로 교체(5서비스) |
| 4 | c3:2 (P1) | lint 가 내부키를 **전체 합산**으로 봐서, 한 서비스만 키를 가져도 통과 | 서비스 단위 검사(ITKO-006) + self-test 케이스 추가(6→7종) |
| 5 | c1:8 · c2:5 · c3:7 (P2) | 양성 대조군이 "401 이 아님" 만 확인 → 403·405·5xx 도 그린 | 구체 상태로 고정: product `405`(admin 은 GET 없음 = 인증통과 증거) · order `200` · payment `404` |
| 6 | c3:6 (P2) | alg 음성이 HS512/unsecured 뿐 — 승인 키의 **RS384/RS512** 를 수용해도 통과 | RS384/RS512 거부 + RS256 양성 대조군 · `exp==iat` · maxTTL 경계(±1s) · skew 경계(±1s) · active/previous kid 회전 추가 (42→48건) |
| 7 | c1:10 · c3:8 (P2) | WebFlux 서명 예산 미측정 | `InternalTokenSigningBudgetTest` 신설 — 예산 **선확정**(RSA-2048 p95 &lt;10ms · 3072 &lt;25ms), 측정 p95 = 1.80ms / 3.00ms. **부하 하 event-loop lag 는 PR3d-b 이연**을 계획서 §9.2 에 명시 |

- 검증: 10모듈 `./gradlew build` 그린 · 가드 5종 그린 · `internal-key-ownership-lint` self-test 7/7
- diff: `.cache/diffs/diff-task-impl3-pr3d-internal-token-1786473047.patch`
- raw: `.cache/codex-reviews/diff-task-impl3-pr3d-internal-token-17864762{57,584}-c{1,2}.json` · `-1786477192-c3.json`

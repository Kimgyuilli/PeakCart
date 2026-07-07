# task-impl3-spring-cloud-gateway — plan audit

## 2026-07-04 02:45 — GP-2 (loop 1)
- 리뷰 항목: 6건 (P0:0, P1:4, P2:2)
- 사용자 선택: [2] 전체 반영 (60s 무응답 → best-judgment: 6건 전부 코드/ADR 검증된 정당 지적, 전량 반영)
- 반영 내역:
  - #1(P1) JWKS 운영조건(cache TTL·last-known-good+alert·Gateway dual-validation) → P12/P18/P21 보강
  - #2(P1) refresh_tokens 기존 데이터 처분(전량 무효화+재로그인, backfill 불가 명시) → P6/P10 보강
  - #3(P1) NetworkPolicy 음성·양성 검증 + GKE overlay 필수 exit → P17/P18/§5 보강
  - #4(P1) ADR immutable 충돌 정정 — ADR-0009:58 에 S9 행 **기존재**(검증으로 확인) → P20 "행 추가"→"존재/owner 검증+코드구현+Layer1 동기화", §4 "ADR 본문 수정 안 함"
  - #5(P2) B11 sweep(application/service 형제라벨+escaped-quote) → P20/P22 보강
  - #6(P2) RS256 서명 latency 측정(D2 KMS 격상 후속조건) → P5 병합(재번호 회피)
- P0 무시 사유: 해당 없음(P0 0건)
- raw: .cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1783132724.json
- run_id: plan:20260704T023818Z:5f91b2fb-0cec-4d3d-b9e6-1feb34715bac:1
- lint: OK (P1..P22 연속, 필수 섹션 6/6)

## 2026-07-04 02:53 — GP-2 (loop 2, 수렴 확인)
- 리뷰 항목: 2건 (P0:0, P1:0, P2:2) — 자동 통과(P1 전멸, 6→2 수렴)
- 사용자 선택: 사용자 요청으로 2차 리뷰 실행 → 2 P2 전량 반영
- 반영 내역:
  - #1(P2) P6 근거 자가당착 정정 — "평문 token→해시 원문 소실 backfill 불가" 모순 제거, "backfill 기술 가능하나 보존 요구 없어 전량 만료"로 수정
  - #2(P2) B5 미처분 — 테스트 키쌍 단일 소유 위치 = `:common` testFixtures 명시(P1 + 영향파일)
- raw: (2nd) plan-task-impl3-spring-cloud-gateway 2차 run
- run_id: plan:20260704T025243Z:5f91b2fb-0cec-4d3d-b9e6-1feb34715bac:2
- 종료: 잔여 P1/P0 0건, 3차 불필요 — 수렴

---

# /work PR1 (RS256/JWKS dual-validation, P1~P5)

## 2026-07-04 09:01 — GW-2 (loop 1)
- 리뷰 run: work:20260704T053356Z:6485a2c5-3e5f-436b-b850-de6e69480ca6:1 (single, diff 1048L)
- 항목: 4건 (P0:0, P1:3, P2:1)
- 사용자 선택: [2] 전체 반영 (모두 정당한 보안 posture 지적)
- 반영 내역:
  - #1(P1) fallback allow-list 과확장(`startsWith("HS")` → HS256/384/512 전부) → 레거시 정확 alg **HS512 단일**로 축소 + HS256-when-on 거부 테스트
  - #2(P1) fallback unbounded(전 서비스 base=true) → base 기본값 **false**(RS256 단일), 전환 배포·전환 테스트만 명시 활성화(bounded, PR4 제거)
  - #3(P1) 개인키 산출물 포함(user main resources) → **제거**. private=`:common` testFixtures(test-scope), public=common main(비밀 아님) 단일화. user @SpringBootTest 3종 + signer 단위테스트가 testFixtures 키 참조. base yml=gitignored 파일 마운트 기본값(k8s CSI=PR3). local-keys/ gitignore
  - #4(P2) 테스트 보강 → alg=none·RS256 kid부재·HS256(비레거시) 거부 + JWKS modulus 선행0 트리밍(256B) 검증
- diff: .cache/diffs/diff-task-impl3-spring-cloud-gateway-1783143202.patch
- raw: .cache/codex-reviews/diff-task-impl3-spring-cloud-gateway-1783143259.json
- 검증: 반영분 targeted 테스트 그린(verifier 단위 + user/notification 통합), 전체 build test BUILD SUCCESSFUL(8모듈, 회귀 0)

## 2026-07-04 — /ship (PR #73)
- drift: `partially_live`(신규/삭제 파일 다수 오탐, blindspot 백로그) — main..HEAD 0 커밋 확인 후 진행
- precheck: ok(warnings 0)
- 커밋: 3 partition(docs/feat/test) + /done 1(docs progress) = 4 커밋, untracked 잔여 0
- PR: https://github.com/Kimgyuilli/PeakCart/pull/73
- /done applied: TASKS ③ 🔲→🔄(PR1 인라인) · PHASE4 PR1 이력 추가. ADR-0013 Accepted 유지(D1/D2 부분 구현). Layer1 미변경(RS256 full 상태는 gateway 완료 후)

## 2026-07-06 09:15 — GP-2 (loop 1, PR2 스코프)
- 리뷰 run: plan:20260706T000419Z:aebee036-ef1f-43d5-915d-0fa3b13651c0:1
- 항목: 4건 (P0:0, P1:2, P2:2)
- 사용자 선택: [2] 전체 반영
- 반영 내역:
  - #1(P1) grace 1회성 원자 소비 불명확(Redis GETDEL→DB 대체 시 이중 발급) → P7 `consumeGraceOnce(tokenHash, now)` 조건부 UPDATE(affected rows=1) 명시 + P8 조회-후-판단 금지 + P10 병렬 2요청 중 1건만 성공 동시성 테스트
  - #2(P1) family deny Redis 키 계약 부재(ADR-0014 D1-c) → P9 키 스펙 명시: `auth:deny:family:<familyId>`(blacklist 신키 동일 네임스페이스 계열)·원문 금지·TTL ≥ access 최대 잔여·User write/Gateway read·miss=통과·조회실패=fail-closed(PR3) + P10 deny 어댑터 키 계약 테스트
  - #3(P2) §5 PR2 검증 범위 부족(:user-service 만) → `:peekcart-common-auth:test :user-service:test` 확장 + P10 verifier/JwtFilter family_id 회귀 명시
  - #4(P2) uk_refresh_tokens_token 드롭 후 대체 unique 부재 → P6 `token_hash CHAR(64) NOT NULL`+`uk_refresh_tokens_token_hash` UNIQUE·해시=TokenHasher.sha256Hex 재사용(실존 확인) + P10 unique index 존재 검증
- 착수 보강(사전, code-verified): P6 마이그레이션 V2·평문 token/uk 드롭·fk 유지 / P7 repository 재정의 / P8 Redis grace 경로 제거·login/logout deleteByUserId→REVOKED 전환 / P9 TokenIssuer.issue 시그니처 seam·TokenClaims 전파
- raw: .cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1783296259.json
- tokens: 159,843

## 2026-07-06 09:20 — GP-2 (loop 2, PR2 스코프)
- 리뷰 run: plan:20260706T001229Z:aebee036-ef1f-43d5-915d-0fa3b13651c0:2
- 항목: 2건 (P0:0, P1:1, P2:1) — loop1 4건 반영분(deny 키 계약·unique) 닫힘 확인
- 사용자 선택: [2] 전체 반영, P1 은 (a)안
- 반영 내역:
  - #1(P1) 전환기 deny read 경로 부재(PR3 전까지 "즉시 차단" 미동작, ADR-0013 D4) → **(a)안**: P9 common-auth `TokenBlacklistLookupPort`/adapter family deny 확장 + `JwtFilter` family_id 전달(hit=401·miss=통과·조회실패=fail-closed). B1 표 확장(PR2)→이동(PR3) 정정, §4·완료조건 동기화 — PR3 이관 대상과 동일 행이라 버려지는 작업 아님
  - #2(P2) grace 성공 후 상태 불변식 미결정(family 내 ACTIVE 2개 가능) → P7 consumeGraceOnce 성공 시 같은 트랜잭션에서 기존 replacement ROTATED 처리 → ACTIVE 정확히 1개 + P10 테스트(replacement 재제시 거부 포함)
- raw: .cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1783296749.json
- tokens: 94,672 (누적 254,515)

## 2026-07-06 09:28 — GP-2 (loop 3, PR2 스코프 — 사용자 요청 추가 루프)
- 리뷰 run: plan:20260706T002238Z:aebee036-ef1f-43d5-915d-0fa3b13651c0:3
- 항목: 2건 (P0:0, P1:2, P2:0) — loop 2 반영분(전환기 enforcement·grace 불변식)의 신규 계약 표면 검증. fail-closed 는 현행 어댑터가 이미 fail-closed 라 posture 변화 아님 확인
- 사용자 선택: [2] 전체 반영
- 반영 내역:
  - #1(P1) family_id 부재 레거시 토큰 계약 없음(NPE·auth:deny:family:null·레거시 전면 401 위험) → P9: absent/null/blank 면 blacklist 만 검사·family deny=miss 취급(claim 부재 ≠ 조회 실패), 신규 발급은 family_id 필수 + P10 레거시(RS/HS fallback) 회귀 테스트
  - #2(P1) grace-success force-rotation 순환 위험 + supersede 된 replacement 의 access token 처분 미정의 → P7: ROTATED-without-grace(grace_until 미부여/≤now, consumeGraceOnce 재성공 순환 금지)·replaced_by_token_id 단방향(자기참조 금지)·access token 은 TTL 까지 bounded overlap 수용(jti blacklist 과설계 미채택) + P10 비순환 확증 테스트
- raw: .cache/codex-reviews/plan-task-impl3-spring-cloud-gateway-1783297358.json
- tokens: 71,450 (누적 325,965)
- attempts 3/3 소진 — 추가 루프는 사용자 명시 확인 필요

## 2026-07-06 11:30 — GW-2 (work loop 1, PR2 구현 diff)
- 리뷰 run: work:20260706T020953Z:a0de8369-43ea-438e-8cc0-d9e676f7e355:1 (single, diff 1671L)
- 항목: 3건 (P0:0, P1:3, P2:0)
- 사용자 선택: [2] 전체 반영 (3건 모두 plan P7/P8 불변식과 정합하는 실제 갭)
- 반영 내역:
  - #1(P1) forceRotate affected-rows 무시 → raw0 grace ↔ raw1 정상 refresh 동시 시 ACTIVE 2개 가능(plan "ACTIVE 1개" 위반) → forceRotate!=1 이면 보수적으로 detectReuse(family revoke+deny) 후 USR-004. INSERT 한 새 토큰도 revoke 로 함께 REVOKED(noRollbackFor 커밋). 통합테스트 graceAndReplacementRefreshConcurrent_neverTwoActive(activeCount<=1) 추가
  - #2(P1) denyFamily(Redis) 실패 시 예외가 RefreshTokenReuseException 아님 → noRollbackFor 미적용 → DB revoke 롤백 → detectReuse 내 denyFamily try/catch 로깅(예외 격리). deny 미기록은 access TTL bounded + blacklist read fail-closed 로 최종 안전. 단위테스트 refresh_reuseWithRedisFailure_stillRevokesFamily 추가
  - #3(P1) REVOKED 재제시가 deny 미기록(plan P8 "이미 revoked family 재제시"=reuse 정의 이탈) → REVOKED status 도 detectReuse 경로로 합류(revoke idempotent + deny 재기록). 테스트 refresh_revokedToken → deny 검증으로 보강
- 리팩터: revokeFamilyAndDeny → detectReuse(예외 반환 헬퍼)로 통합, 3개 reuse 진입점(ROTATED 초과·forceRotate miss·REVOKED) 단일화. @Slf4j 추가
- 검증: :peekcart-common-auth:test :user-service:test BUILD SUCCESSFUL(회귀 0)
- diff: .cache/diffs/diff-task-impl3-spring-cloud-gateway-1783303116.patch
- raw: .cache/codex-reviews/diff-task-impl3-spring-cloud-gateway-1783303825.json
- tokens: 76,429

## 2026-07-06 11:38 — GW-2 (work loop 2, 재리뷰)
- 리뷰 run: work:20260706T023349Z:a0de8369-43ea-438e-8cc0-d9e676f7e355:2 (single, diff 1780L)
- 항목: 1건 (P0:0, P1:0, P2:1) — loop1 반영분 트랜잭션/롤백 semantics 정합 확인, 테스트 갭 1건
- 사용자 선택: [2] 전체 반영
- 반영 내역:
  - #1(P2) graceAndReplacementRefreshConcurrent 는 swallow+activeCount<=1 이라 "forceRotate miss → 새 토큰까지 family 전체 REVOKED 커밋" 불변식을 직접 고정 못함 → 결정적 통합테스트 graceSuccessButReplacementAlreadyRotated_revokesWholeFamily 추가(raw0 grace 유효 + raw1 사전 ROTATED → forceRotate=0 결정적 진입 → activeCount=0·non-REVOKED=0·deny 기록 검증)
- 검증: :user-service:test RefreshTokenReuseIntegrationTest BUILD SUCCESSFUL
- raw: .cache/codex-reviews/diff-task-impl3-spring-cloud-gateway-1783305262.json
- tokens: 59,514 (work 누적 135,943)

## 2026-07-06 — /ship (PR #74)
- precheck: ok(warnings 0)
- 커밋: 3 partition(feat/test/docs) + /done 1(docs progress) = 4 커밋
- PR: https://github.com/Kimgyuilli/PeakCart/pull/74
- /done applied: TASKS ③ PR2 인라인(🔄 유지, PR3/PR4 대기) · PHASE4 PR2 이력 추가. ADR-0013 Accepted 유지(D4 구현). REQUIRES_NEW→noRollbackFor 전환은 구현 디테일(progress 기록, 신규 ADR 불요). Layer1 미변경(header-trust 전환은 PR3).

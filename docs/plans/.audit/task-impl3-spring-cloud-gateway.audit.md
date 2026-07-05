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

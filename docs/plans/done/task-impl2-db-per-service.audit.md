
## 2026-06-22 04:02 — GW-2 (work loop 1, PR2 — split 3 chunks)
- 브랜치: feat/task-impl2-pr2-db-physical-split (main 분기, PR1 #69 머지 후)
- 구현: P4(5 per-service V1__init_<svc>.sql consolidation 베이스라인) · P5(flyway.enabled per-service true·order B5 특권 제거) · P6(datasource …/peekcart_<svc>+계정 분화 10 yml) · P7(:common/db/migration 삭제·build.gradle flyway 플러그인/flywayMigrateShared 제거) · P8(outbox allowlist 제거 3서비스+테스트 갱신·ProductOutboxOwnership 재작성) · P9(mysql init ConfigMap .sh+Secret env·mysql-secret 5 pw·5 per-svc secret 분화) · P10(compose down -v 롤백) · P11(비-order initContainer 게이트 제거) · P12(smoke flyway 스텝 제거·compose mysql init SQL) · P13(통합테스트 cross-domain 시드 제거: notification/order×2/payment 임의 ID·cleanDatabase 스키마 적응형) · P14(ADR-0016 신규·ADR-0012 Partially Superseded·README·05/02 Layer1)
- build: ./gradlew build test 8모듈 BUILD SUCCESSFUL (10m35s, P15 그린)
- 리뷰 run: split 3 chunks (c1/c2/c3), aggregate_result=ok, tokens 169k/231k/357k
  - c1/c2/c3 (P0:0, P1:1, P2:3 dedupe) — 사용자 [2] 전체 반영
  - #1(P1, c3:1=c1:1=c2:1 보안) GRANT 에 DROP 포함 → P9 명시목록 초과·최소권한 위반(Flyway baseline 은 CREATE/ALTER/INDEX/REFERENCES+DML 로 충분). → k8s init ConfigMap + compose init SQL GRANT 에서 DROP 제거.
  - #2(P2, c1:2 test 격리 버그) cleanDatabase SET FOREIGN_KEY_CHECKS=0 은 세션 변수라 rollback 미복구 → 커넥션 풀 오염. → finally 에서 best-effort FK_CHECKS=1 복구.
  - #3(P2, c1:3 거버넌스) ADR-0012 무효화범위가 Status 줄 바로 아래 아님(README:10). → 무효화범위 블록을 Status 직하로 이동.
  - #4(P2, c2:2 Layer1) Order DB ERD 에 V6/V9/V11 컬럼·product_price_cache 누락. → ERD 보강(P14 완결).
- diff: .cache/diffs/diff-task-impl2-db-per-service-1782067226.patch (78 files, ~2.1k L; split chunks-task-impl2-db-per-service/)
- raw: .cache/codex-reviews/diff-task-impl2-db-per-service-c{1,2,3}-*.json
- 반영 후 build 재검증 진행
- 범위: PR3(P16~P19 retention 스케줄러)는 별도 PR.

## 2026-06-21 13:31 — GP-2 (loop 1)
- 리뷰 항목: 5건 (P0:1, P1:2, P2:2)
- 사용자 선택: [2] 전체 반영 (#1~#5)
- 반영 요약:
  - #1(P0) ADR-0012 D1 표↔코드 드리프트(stock_reservations/payment_cancellations) → 코드 정본 + ADR-0012 update-log + Layer1 동기화(P13) 명시. 상단 ⚠️·B2 보강
  - #2(P1) 전환 모드 dev/test 데이터 폐기(volume/PVC 재생성·롤백) → P9b 신설
  - #3(P1) B1b k8s 리소스명 스윕(secretKeyRef/configMapKeyRef) → B1b 보강·P9 sweep
  - #4(P2) D5 retention fail-fast + floor 설정키 계약 → P15/P17
  - #5(P2) 물리격리 판정 SQL(information_schema·GRANT·flyway_schema_history) → P14/§5
- raw: .cache/codex-reviews/plan-task-impl2-db-per-service-1782048259.json
- run_id: plan:20260621T132355Z:aba0982a-edc1-4f01-9a6c-75181902e95a:1

## 2026-06-21 13:43 — GP-2 (loop 2)
- 리뷰 항목: 5건 (P0:0, P1:2, P2:3)
- 사용자 선택: [2] 전체 반영 (#1~#5)
- 반영 요약:
  - #1(P1) ADR 거버넌스 — update-log 일괄이 부정확(README:8,14). payment_failures 정정=update-log, stock_reservations 모델변경=신규 ADR-0016 + ADR-0012 Partially Superseded. 상단 ⚠️·B2·P14·§4 보정
  - #2(P1) P9 GRANT 문법 MySQL 호환(CREATE USER + GRANT … ON peekcart_<svc>.*)
  - #3(P2) flyway 이력 검증 오판(전 서비스 V1 → version disjoint 불가) → script 파일명·테이블귀속 판정(P15/§5)
  - #4(P2) D5 fail-fast 문구 잔존 → PR3 성공기준·완료조건 "거나 경고" 삭제
  - #5(P2) ID 규약 — P9b→P10, P10~P18→P11~P19 재번호(P1~P19)
- raw: .cache/codex-reviews/plan-task-impl2-db-per-service-1782048800.json
- run_id: plan:20260621T133259Z:aba0982a-edc1-4f01-9a6c-75181902e95a:2

## 2026-06-21 14:04 — GW-2 (work loop 1, PR1)
- 리뷰 run: work:20260621T140213Z:bf3aa768-5c9c-42d8-bed1-abac32471a41:1
- 항목: 0건 (P0:0, P1:0, P2:0) — 자동 통과
- 구현: P1(V13 교차 FK 6개 드롭) · P2(소유경계 검증 — 교차 매핑 0) · P3(build test 8모듈 그린 9m46s)
- diff: .cache/diffs/diff-task-impl2-db-per-service-1782050492.patch
- raw: .cache/codex-reviews/diff-task-impl2-db-per-service-1782050555.json

## 2026-06-21 14:09 — GS-1 (ship, PR1)
- precheck: warnings — [MISS] ADR-0016
- 선택: [2] 무시하고 진행
- 사유: ADR-0016 은 PR2(P14) 산출물 — PR1 범위 아님. 계획서가 선참조한 것이며 PR2 에서 생성됨

## 2026-06-21 14:11 — /done applied (PR https://github.com/Kimgyuilli/PeakCart/pull/69)
- TASKS.md ② → 🔄 (PR1 #69 링크)
- PHASE4.md 구현 ② PR1 이력 추가
- 커밋 3개(feat(db) V13 · docs(plan) · docs(progress))

## 2026-06-22 02:20 — GP-2 (loop 1, PR2/PR3 재리뷰)
- 컨텍스트: PR1(#69) 머지 완료 → 이번 리뷰는 PR2(P4~P15)·PR3(P16~P19) 초점. PR1 항목 제외.
- 리뷰 항목: 5건 (P0:0, P1:3, P2:2)
- 사용자 선택: [2] 전체 반영 (#1~#5, 모두 파일·라인 인용 확증)
  - #1(P1, ADR 거버넌스) payment_failures→payment_cancellations 는 단순 사실 오류 아닌 D1 결정 테이블 변경(README:11-14 update-log 우회 금지). → P14 재판정: payment_cancellations 도 ADR-0016 supersede 범위, ADR-0012 Partially Superseded 범위 = D1 Product·**D1 Payment**·D3. update-log 정정 철회.
  - #2(P2, 영향파일 누락) §4 에 docs/adr/README.md 부재. ADR-0016 추가 시 인덱스 행+ADR-0012 status 변경 필요(README:20-21,37). → P14(c)·§4 에 README.md 갱신 명시 + PR2 완료조건 추가.
  - #3(P1, k8s 보안) P9 가 CREATE USER … IDENTIFIED BY <literal> 을 ConfigMap 에 둠 → 비밀번호 평문 노출·per-service secret 분화와 충돌. → P9 에 ⚠️ 비밀번호 위치: ConfigMap literal 금지, Secret env entrypoint(.sh) 생성 또는 init SQL Secret 마운트(택1) + 렌더 ConfigMap literal 0 검증.
  - #4(P1, k8s 검증) PR2 검증이 grep/gradle/compose-smoke/SQL 에 머묾, k8s 배포 검증 부재(Phase4 Exit=독립배포·B1b 이름참조 누락 경고). → P15 에 (5)kustomize+apply --dry-run, (6)minikube apply+rollout status, (7)Pod 자기 secret/DB URL 기동, (8)이름참조 처분표 기록 추가.
  - #5(P2, Dockerfile) 마이그레이션 모듈 이동분 이미지 포함 검증 부재(B5/PLAN-BLINDSPOTS:42-45, 불변 줄에만). → P12 에 <svc>:ci 이미지 BOOT-INF/classes/db/migration/V1__init_<svc>.sql 존재 assert(또는 부팅로그 Flyway 적용) 추가.
- 검증: hpx_plan_lint OK (반영 후 재확인)
- raw: .cache/codex-reviews/plan-task-impl2-db-per-service-1782061754.json
- run_id: plan:20260621T170914Z:b8f23d3d-4781-4734-b670-b1cecbaa4949:1
- tokens: 97,656

## 2026-06-22 02:38 — GP-2 (loop 2, PR2/PR3 재리뷰)
- 리뷰 항목: 2건 (P0:0, P1:1, P2:1)
- 사용자 선택: [2] 전체 반영 (loop1 5건 반영 정확성 확인 + 신규 2건)
  - #1(P1, 문서 내부 모순 — loop1 #1 미전파) P14 는 payment_failures→payment_cancellations 를 '결정 변경→ADR-0016 supersede'로 고쳤으나 상단 ⚠️(:5)·§2 표주석(:36)·B2(:76)는 'update-log OK'로 잔존. → 3곳 모두 P14 기준 통일: 두 드리프트(stock_reservations·payment_cancellations) 모두 ADR-0016 supersede, ADR-0012 Partially Superseded by ADR-0016, README 인덱스 갱신, update-log 표현 제거.
  - #2(P2, B1b 처분표 부재) §2 B1b(:60) 가 '아래 P9-sweep 표' 참조하나 표 부재, P15 는 '나중 기록'만. → §2 에 실제 P9-sweep 처분표 추가(grep 으로 현재 name 확정): mysql-secret(root 유지·MYSQL_USER/DATABASE 삭제), 신설 mysql-init-config(ConfigMap DDL+Secret pw 경로), <svc>-secret(값 분화·name 불변), <svc>-config(유지), 비-order initContainer(삭제 P11). 열=리소스/현재name/PR2이후/처분/검증.
- 검증: hpx_plan_lint OK (반영 후 재확인)
- raw: .cache/codex-reviews/plan-task-impl2-db-per-service-1782062563.json
- run_id: plan:20260621T172243Z:b8f23d3d-4781-4734-b670-b1cecbaa4949:2
- tokens: 106,126

## 2026-06-22 04:40 — /done applied (PR https://github.com/Kimgyuilli/PeakCart/pull/71)
- TASKS.md ② PR2 ✅ (#71 링크) — ② 전체는 PR3(retention) 남아 🔄 유지
- PHASE4.md 구현 ② PR2 이력 추가 (P4~P14·핵심결정·프로세스)
- ADR-0016 신규(Accepted)·ADR-0012 Partially Superseded·README 인덱스 (P14 에서 반영 완료)
- 커밋 9개(feat(db)·refactor(outbox)·chore(config)·chore(build)·chore(k8s)·test·docs(adr)·docs + docs(progress) done)


## 2026-07-04 00:00 — GP-2 (loop 1)
- 리뷰 항목: 5건 (P0:0, P1:3, P2:2) — focus PR3
- 사용자 선택: [2] 전체 반영 (5/5)
- 반영: P1#1 서비스×잡 매트릭스+컨텍스트 테스트 · P1#2 outbox FAILED/PENDING/NULL 보존 predicate+테스트 · P1#3 kafka-topic-retention floor SSOT audit 판정 · P2#4 정책키 base-only grep · P2#5 §2 트레이드오프+§6 replay 정책
- P0 무시 사유: 해당 없음(P0 0건)
- raw: .cache/codex-reviews/plan-task-impl2-db-per-service-1783122178.json
- run_id: plan:20260703T234225Z:a74df9bc-ae0e-4660-9893-b72464de9305:1

## 2026-07-04 00:08 — GP-2 (loop 2)
- 리뷰 항목: 3건 신규 (P0:0, P1:2, P2:1) — 1회차 5건 반영 확인됨(라인 인용)
- 사용자 선택: [2] 전체 반영 (3/3)
- 반영: P1#1 floor 교차필드 불변식→common 단일 typed @ConfigurationProperties+@AssertTrue(4 Duration max≤retention)·소유4서비스 @EnableConfigurationProperties fail-fast · P1#2 대량삭제 배치계약(cutoff 1회·batch-size/max-batches-per-run·삭제기준 인덱스)+다중batch 테스트 · P2#3 bean 활성화=소유 서비스 물리배치 확정(global 복제 패턴·user=0 자연성립)
- P0 무시 사유: 해당 없음(P0 0건)
- raw: .cache/codex-reviews/plan-task-impl2-db-per-service-1783123362.json
- run_id: plan:20260704T000210Z:a74df9bc-ae0e-4660-9893-b72464de9305:2

## 2026-07-04 00:48 — GW-2 (work loop 1, single)
- 리뷰 run: work:20260704T003858Z:a74df9bc-ae0e-4660-9893-b72464de9305:1
- 항목: 3건 (P0:0, P1:0, P2:3) — 자동 통과(P0/P1 0)
- 결정: P2 3건 전부 반영(플랜 P18 커버리지 완결)
  - P2#1 5서비스 매트릭스: order/payment(both-bean)·user(cleanup·retention props 부재) 테스트 추가
  - P2#2 outbox 다중 batch: batch-size=2·PUBLISHED-old 5건 단일 cleanup 다중 batch 검증
  - P2#3 base-only 정적 가드: product 프로파일 yml 정책키 부재 테스트(RetentionPolicyPlacementTest) + 전서비스 verify grep
- diff: .cache/diffs/diff-task-impl2-db-per-service-1783125420.patch
- raw: .cache/codex-reviews/diff-task-impl2-db-per-service-1783125566.json

## 2026-07-04 02:12 — /done applied (PR https://github.com/Kimgyuilli/PeekCart/pull/72)
- 커밋 3(feat/test/docs) + docs(progress) 1 = 4, push origin/feat/impl2-pr3-retention-scheduler
- TASKS ② 🔄→✅ (#72, L-008/011 종결)·focus→③ / PHASE4 PR3 이력
- ADR 변경 없음(ADR-0012 D5 기존)

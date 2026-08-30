## 2026-08-30 — 계획 리뷰 라운드 1

- 항목: 7건 (P0:0, P1:6, P2:1)
- 처리: 반영 7건 / 기각 0건 (단 #1 은 "새 ADR 작성" 제안을 progress 기록으로 대체 — ④ 선례)
- 뒤집힌 전제:
  - **§2.3-c 반증** — 초안은 "`CachingConfigurer#cacheManager()` 오버라이드 시 빈 이름이 바뀌어 S7 의 `cache_manager=\"cacheManager\"` 라벨이 깨진다"고 적었으나 **틀렸다**. 라벨은 `@Bean` 메서드 이름에서 오고 오버라이드는 빈 이름을 바꾸지 않는다. 결론(미오버라이드)은 유지하되 근거를 "불필요한 명시 배선·CacheManager 모호성 회피"로 교체.
  - **§2.4 put 실패 "무해" 반증** — 응답 정확성은 유지되나 캐시가 안 채워져 **모든 후속 요청이 DB 를 친다**. Redis 장애의 DB 전이. P6/V5 신설.
  - **작업 항목 형식** — `.claude/scripts/lib/sync.sh:38-44` 의 `hpx_plan_lint` 는 `- [ ] **P1.**` 형식만 stable id 로 인식. `### P1.` 형식은 0개로 판정. 필수 섹션 "목표/목적"·"영향 파일"도 부재. 실행으로 확인 후 전면 재구성.
- 신설: Toxiproxy 하네스(무응답 검증), P7 운영/배포 계약, PHASE4 귀속 차이 절, §4 영향 파일
- raw: `.cache/codex-reviews/plan-task-impl5-cqrs-cache-fallback-1788050579.json`

## 2026-08-30 — 계획 리뷰 라운드 2

- 항목: 10건 (P0:0, P1:6, P2:4)
- 처리: 반영 9건 / 부분 1건 (#5 ADR 판정 — 근거만 교체, 결론 보류 후 3라운드에서 재지적)
- 뒤집힌 전제:
  - **V3 의 2s 상한 산술 오류** — `@Cacheable` 1회 요청이 **get 실패 → DB → put 실패**로 command timeout 을 **2회** 소비. 1s×2 = 2s 라 상한이 성립 불가. → timeout **500ms**, 상한 **1.5s** 로 재설정.
  - **`hpx_plan_lint` 가 zsh 에서 실행 불가** — `sync.sh:10` 의 `local path=` 가 zsh 의 `path`↔`PATH` 연동을 건드려 `PATH` 를 `docs/plans/...` 로 덮고 다음 줄 `python3` 가 죽는다. `zsh -c` / `bash -c` 대조로 재현 확인. → P10 신설.
  - **Toxiproxy 배선 누락 시 전면 false-green** — backend Redis 의 `@ServiceConnection` 을 유지한 채 프록시를 추가하면 앱 트래픽이 프록시를 우회. → `@ServiceConnection` 제거 + `@DynamicPropertySource` + V0 경유 단언.
  - **Toxiproxy 로 SET 만 실패시킬 수 없다** (L4 프록시, Redis 명령 미해석) → read-only ACL 대신 전면 장애 + `ProductRepository` 스파이로 변경.
- 신설: V0(하네스 자체 검증), V7(프로파일 바인딩 회귀), M7(cache stampede/DB pool), P10
- raw: `.cache/codex-reviews/plan-task-impl5-cqrs-cache-fallback-r2-*.json`

## 2026-08-30 — 계획 리뷰 라운드 3 (상한)

- 항목: 4건 (P0:0, P1:3, P2:1)
- 처리: 반영 3건 (#2·#3·#4) / **미해결 1건 (#1 — 사용자 판단 대기)**
- 뒤집힌 전제:
  - **P10 의 1줄 수정이 오히려 함수를 확실히 깬다** — `:10` 선언만 `plan_path` 로 바꾸고 `:11` 의 `python3 - "$path"` 참조를 남기면 bash 에서 빈 인자, zsh 에서 특수배열이 전달된다. → 선언+참조 동시 변경으로 정정.
  - **연결 거부와 무응답은 다른 기구다** — bandwidth toxic 은 정체만 만들고 거부하지 않는다. V1 = `Proxy#disable()`, V3 = downstream `timeout(_, 0)` toxic 으로 분리.
  - **`hpx_plan_lint` 는 제목 존재와 P 연속성만 본다**(`sync.sh:25-44`) — runbook 내용·임계치·문서 정합은 빈 제목으로도 통과. → V9 grep 계약 신설, 완료 조건 12(의미 정합)는 기계 판정에서 제외해 리뷰 체크로 분리, P7 감시 임계를 PromQL 수치로 고정.
- **미수렴 항목 (#1, P1)**: §2.1-a 의 "ADR-0012 에 정정할 사실 오류가 없다" 판정. 리뷰어는 ADR-0012:53 의 `"product_cache/장바구니 조회 충족"` 과 실제 `product_price_cache`(4컬럼, price/version 만 소비)의 차이를 **문언 재해석**으로 보고 README:11(Update Log) 또는 :14(새 ADR) 표면을 피하지 못한다고 지적. 라운드 상한 도달 → **사용자 판단으로 이관**.
- raw: `.cache/codex-reviews/plan-task-impl5-cqrs-cache-fallback-r3-*.json`

## 2026-08-30 — 미수렴 항목 처분 (사용자 판단)

- **r3 #1 (P1) 처분: 반영.** ADR-0012 를 `README:11-13` 의 **Update Log 경로**로 정정하기로 사용자가 결정.
- 초안 §2.1-a 의 "정정할 사실 오류가 없다" 논거는 **철회**. `:53` 의 `"product_cache/장바구니 조회 충족"` 은 소비처를 명시한 문장이 맞고, "payload 필드 계약일 뿐"이라는 축소는 결론에 맞춘 재해석이었다.
- 분류 근거: 틀린 것은 **테이블명·컬럼 범위·소비처 구현 상태**라는 사실 진술(README 예시의 "파일명·Phase 귀속·수치" 계열)이다. 결정 자체(이벤트로 동기 호출 대체, 7필드 계약)는 유효 → `:14`(트레이드오프 변경·Consequences 재해석 → 새 ADR)에 해당하지 않는다.
- 조치: **P11 신설** — ADR-0012 본문 정정 + `## Update Log` 절 + `fix(adr):` 분리 커밋. Status·ADR 개수 무변경. V9 grep 계약과 완료 조건 12 에 반영.
- 수렴 판정: 3라운드 P1 3건 중 2건은 코드 사실 확인 후 즉시 반영, 나머지 1건이 본 처분으로 종결 → **P1 = 0, 새 계약 표면은 P11(ADR Update Log) 하나이며 이는 리뷰 지적의 직접 수용이라 신규 리스크 표면이 아님.**

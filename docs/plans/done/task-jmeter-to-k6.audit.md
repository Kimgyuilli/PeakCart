# task-jmeter-to-k6 — /work audit log

## 2026-04-23 21:51 — GW-2 (loop 1)
- 리뷰 run: `work:20260423T124428Z:881a1fdd-8c50-407f-bc4a-9ec3069dadaf:1`
- 항목: 5건 (P0:1, P1:3, P2:1)
- 사용자 선택: "동의하는 부분에 대해 반영" — 판단 기반 전체 반영 (5건 모두 기술적으로 타당)
- 적용 요약:
  - #1 (P0) `order-concurrency.js` 가 `ramping-vus` 하에서 default function 을 무한 반복 → 1m hold 구간 동안 VU 당 여러 주문 시도 발생. 재고 1,000 + verify-concurrency.sql "1회 실행" 전제와 충돌. `__ITER === 0` 가드 추가로 VU 당 주문 1회 강제, 이후 iteration 은 `sleep(10)` 으로 VU 생존만 유지 (Grafana 타임라인 관찰용)
  - #2 (P1) accessToken 추출 실패가 조용한 return → check 에 `'login 200 + token present': r.status===200 && !!accessToken` 병합. `peekcart_auth_failures` Counter + threshold `count<10` 추가로 토큰 누락이 threshold 에 가시화
  - #3 (P1) cart 201 실패 무시하고 주문 진행 → `cartOk` check 실패 시 return + `peekcart_cart_failures` Counter 로 계수
  - #4 (P1) TEMPLATE.md 시나리오 2 정의가 "loop 1 / 전체 요청 1000" 고정으로 현실과 괴리 → "ramp-up 30s → 1m hold → 30s ramp-down, VU 당 1회 (`__ITER === 0` 가드), 총 시도 1,000" 으로 재서술
  - #5 (P2) README §C `tee .../sql/...` 앞에서 `sql/` 디렉토리 미생성 → `mkdir -p loadtest/reports/YYYY-MM-DD/{sql,grafana}` 으로 교체
- diff: `.cache/diffs/diff-task-jmeter-to-k6-1776948169.patch` (601줄, 13 files; 212줄은 .jmx 순수 삭제)
- raw: `.cache/codex-reviews/diff-task-jmeter-to-k6-1776948553.json`
- tokens used: 56,582
- 비고: 로컬 k6 리허설은 의도적으로 deferred (PHASE3 엔트리 명시). attempts 1/3, cycle 4/5 — 루프 종료 판단

## 2026-04-24 — /done applied (PR https://github.com/Kimgyuilli/PeakCart/pull/26)
- TASKS.md: 변경 없음 (Task 3-4 L395/L396/L397 은 세션 C 실행 대기, 🔲 유지. 본 PR 은 "스크립트/문서 준비"까지이며 TASKS 상의 설치·실행 행과 1:1 매칭되지 않음)
- ADR: 신규·전환 없음 (계획서 §7 "ADR 신규 작성 없음" 충실 이행)
- PHASE3.md: /work 단계에서 이미 2026-04-22 엔트리 기록됨. 프로젝트 관행상 PHASE 엔트리에 PR URL 미기록 — git log 로 추적 가능
- Layer 1 docs: 본 PR (p2) 에서 갱신 완료


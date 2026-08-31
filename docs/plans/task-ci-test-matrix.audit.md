# task-ci-test-matrix — 계획 리뷰 audit

## 2026-08-31 — 계획 리뷰 라운드 1
- 항목: 9건 (P0:0, P1:4, P2:5) / 반영 9건 · 기각 0건
- 뒤집힌 전제:
  - "테스트 없는 2모듈은 매트릭스에서 빼도 된다" → 루트 `build` 는 **154 태스크**이고 `internal-token-contract:testFixturesJar`(다른 모듈 테스트가 의존)·루트 자신의 `:assemble/:build/:check/:jar` 포함
  - artifact 배치가 저절로 복원된다 → `upload-artifact` 는 LCA 상대경로, `download-artifact` 는 이름 디렉터리 아래 → staging 필요
  - reports 를 test-results 로 대체 가능 → **ADR-0011 §D4 가 `**/build/reports/` 일반화를 결정**. 위반
  - "루트 한정 = 가볍다" → 가드가 전 서비스 `:classes` 를 dependsOn 하므로 전 모듈 main 컴파일 수행
  - 합산 러너 시간이 과금 지표 → 레포가 **PUBLIC** 이라 billable minutes 없음
  - "lint 14종" → 파일 수였고 CI 는 **고유 11 스크립트 / 18 호출**
- 범위 변화: P9(태스크 parity lint) 신설, shard 8 → 6 이되 덮는 모듈 8 → 10
- raw: `.cache/codex-reviews/plan-task-ci-test-matrix-1788103056.json`

## 2026-08-31 — 계획 리뷰 라운드 2
- 항목: 10건 (P0:0, P1:6, P2:4) / 반영 10건 · 기각 0건
- **1R 수정이 만든 새 결함 7건**
- 뒤집힌 전제:
  - staging/manifest 스텝이 Gradle 실패 후에도 돈다 → GitHub Actions 후속 스텝 기본 조건은 **`success()`**
  - 무테스트 모듈도 산출물 디렉터리를 만든다 → **둘 다 부재**(실측). "디렉터리 실재" 요구 시 정상 shard 실패
  - 양방향 집합 대조가 중복 배치를 막는다 → 막지 못함. `merge-multiple` 은 **last-writer-wins**
  - P9 가 의미 있는 검사다 → **실측 154 == 154, 차이 0**. 모듈 집합이 같으면 태스크 집합은 자동으로 같음 → **P9 폐기**
  - 루트에는 보존할 report 가 없다 → `build/reports/problems/` 존재, 현 CI 가 보존 중
- 범위 변화: 작업 항목 9 → 8, 신설 lint 2 → 1
- raw: `.cache/codex-reviews/plan-task-ci-test-matrix-1788103663.json`

## 2026-08-31 — 계획 리뷰 라운드 3 (상한) — **설계 단순화**
- 항목: 9건 (P0:0, P1:6, P2:3) / 반영 9건 · 기각 0건
- **2R 수정이 만든 새 결함 6건**
- **패턴 판정**: 9건 중 **4건이 1~2R 에서 쌓은 manifest·rc 기구에서만** 나왔다. 방어 장치가 결함 생산원이 됐다고 보고, 지적을 하나씩 메우는 대신 **기구를 걷어냈다**(CLAUDE.md §2)
  - rc 배관 폐기 → Gradle 스텝을 그냥 실패시키고 후속에 `!cancelled()`. #2·#3 설계로 소멸
  - manifest 폐기 → 기대 목록을 정적 매핑(D3)에서. #4 소멸
- 뒤집힌 전제:
  - `run_attempt` 이름 격리가 재실행을 안전하게 한다 → **정반대**. "Re-run failed jobs" 는 실패 job 만 재실행하므로 성공 shard 가 옛 이름으로 남아 attempt 한정 pattern 이 **반드시 깨진다** → 고정 이름 + `overwrite: true`
  - gate 가 `needs: test` 만으로 guards artifact 를 쓸 수 있다 → 병렬이라 없을 수 있음
  - "워크플로 안에 명시" 가 lint 의 계약이 된다 → 스키마 미정이면 lint 가 자기 복사본만 보는 false-green
  - 후속 스텝이 이전 스텝 콘솔을 읽을 수 있다 → 불가. `tee` 필요
  - T7 의 20분·2.0 이 타당 → 모듈별 시간 자료 없음 + platform(컨테이너 0)과 order(55) 를 같은 비율로 묶는 것이 부당 → **사전 임계값 철회**
- 범위 변화: 작업 항목 8 → **7**, manifest 스키마·rc 프로토콜 2개 소멸
- **수렴 미달** — P1 = 6. 라운드 상한 3 도달
- raw: `.cache/codex-reviews/plan-task-ci-test-matrix-1788104199.json`

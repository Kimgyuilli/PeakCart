# task-adr-observability-ssot — 관측성 계약 SSOT ADR-0009 작성

> 작성: 2026-05-04
> 관련 Phase: Phase 3 잔여 부채 (Phase 4 MSA 분리 진입 전 처리)
> 관련 부채: D-005 (Observability) — `docs/TASKS.md` Tech Debt 표
> 선행 task: 본 task 종결 후 `task-d005-observability-consolidation` 에서 ADR-0009 의 결정을 코드/매니페스트에 반영
> 관련 ADR: 신규 작성 = **ADR-0009** (Status: Proposed → Accepted). ADR-0007 (YAML 프로파일 원칙) 의 후속/확장. ADR-0006 (Monitoring 환경 분리) 와 surface 분담 정합 필요.

## 1. 목표

D-005 의 본질은 "관측성 계약(metrics 정책 / 태그 / 노출 / 보안 / 스크랩 / 알림) 의 **SSOT 가 어디인가**" 라는 결정이 부재한 것이다. 현재 9개 surface (6 family — S1~S5 + S6.a~d) 가 5파일에 흩어져 있고, 각 파일은 개별적으로는 정확하나 전체 계약 일관성이 자동으로 보장되지 않는다.

본 task 의 **primary 산출물은 ADR-0009** — 각 surface 의 SSOT 위치를 명시한 결정 문서. 그 ADR 이 활성 결정으로 동작하기 위해 필요한 최소 동기화 (ADR 인덱스 행, CLAUDE.md 참조 단락, TASKS.md / PHASE3.md 이력) 는 본 task 범위에 포함한다. 코드/매니페스트 통합은 비대상.

세부 목표:

- **(D1) 5파일 의존 관계 감사** — 9개 surface (6 family — S1~S5 + S6.a~d) 의 현재 위치, 의존 방향, 변경 시 파급을 본문 §Context 에 표 형태로 기록 (정확성 검증 가능 형태)
- **(D2) SSOT 결정** — 각 surface 별로 "SSOT 가 어느 파일/어느 레이어인가" 명시. ADR-0007 의 "동작 정책 → Java Config" 원칙을 관측성 영역에 구체화하여 확장
- **(D3) Phase 4 행동 지침** — Gradle 멀티모듈 분리 시 각 surface 가 어느 모듈/서비스로 이동하는지 본문에서 미리 결정. 본 ADR 이 Phase 4 모듈 분리에서 다시 흩어지는 것을 막는 것이 핵심 목적
- **(D4) 회귀 강제 메커니즘** — `@AutoConfigureObservability` 회귀 테스트 (P1-D 에서 도입) 가 SSOT 강제로 격상되는 방식 명시. 본 task 는 메커니즘 정의만, 코드 변경은 비대상

본 task 는 **문서만 변경** 한다. 실제 코드/매니페스트 통합은 후속 task `task-d005-observability-consolidation` 에서 본 ADR 결정을 따라 진행.

## 2. 배경 / 제약

### 발견 경위 (D-005)

- 2026-04-10 전반적 리뷰 종합에서 "관측성 계약 5파일 분산" 으로 등록
- 1차 봉합 완료 (2026-04-12):
  - **P0-B**: `management.metrics.tags.application` + `management.endpoints.web.exposure.include` 를 base `application.yml` 로 이동 (ADR-0007 감사표 기준)
  - **P1-D**: `@SpringBootTest` + `@AutoConfigureObservability` 회귀 테스트 — `/actuator/prometheus` 응답에서 histogram bucket + `application="peekcart"` 태그 검증 (D-001 재발 방지)
- TASKS.md 우선순위 컬럼: `중간 — 1차 봉합 완료, 잔여 리스크`
- "완전 해결은 Phase 4 전 관측성 단일 설계 축 정리" 가 명시된 후속 — 본 task 가 그 "단일 설계 축" 자체.

### 9개 surface (6 family — S1~S5 + S6.a~d) 와 현 위치 (감사 결과 — ADR 본문 §Context 의 골자)

| # | Surface | 현 위치 | 의존 surface | 변경 시 파급 |
|---|---------|---------|--------------|--------------|
| S1 | HTTP histogram bucket (p50/p95/p99 계산 가능성) | `MetricsConfig.java` (`MeterRegistryCustomizer`) | — | Grafana p95/p99 패널 + S6.b (latency alert) **만**. error-rate alert 는 `_count` 사용으로 무관 |
| S2 | metrics tags (`application=peekcart`) | `application.yml` `management.metrics.tags.application` | — | Micrometer 가 발행하는 **앱 series** (예: `http_server_requests_*`) 의 `application=` 라벨. Prometheus `up` series 는 ServiceMonitor scrape 라벨이 별도라 무관. 영향: S6.a/b (application 필터 사용) |
| S3 | actuator 노출 (`exposure.include: health,prometheus`) | `application.yml` `management.endpoints.web.exposure.include` | — | scrape 가능 여부, S4 와 짝 |
| S4 | actuator 보안 허용 (`/actuator/health/**`, `/actuator/prometheus` permitAll) | `SecurityConfig.java` | — | scrape 응답 코드 (200 vs 401), K8s liveness/readiness Probe |
| S5 | scrape 설정 (interval 15s, port `name: http`, label `release`) | `k8s/base/services/peekcart/servicemonitor.yml` | — | metric 수집 빈도, Helm Prometheus operator 매칭, **모든 S6 alert** 가 S5 결손 시 발화 불가 (또는 absent 발화) |
| S6.a | error-rate alert (5xx ratio) | `k8s/monitoring/shared/grafana-alerts.yml` L27, 36 | S2 (`application=`), S5 (scrape) | `_count` rate 비율 — S1 histogram bucket 무관 |
| S6.b | latency alert (p95 > 2s) | `k8s/monitoring/shared/grafana-alerts.yml` L63 | S1 (`_bucket`), S2, S5 | `histogram_quantile(0.95, ..._bucket{application=...})` |
| S6.c | target-down (`up==0`) | `k8s/monitoring/shared/grafana-alerts.yml` L94 | S5 (scrape target/labels) | `up{namespace=peekcart, service=peekcart}` — S1/S2 무관 |
| S6.d | scrape-absent (series 부재) | `k8s/monitoring/shared/grafana-alerts.yml` L121 | S5 (scrape target 등록 자체) | `absent(up{...})` — S1/S2 무관 |

### ADR-0007 과의 관계

- ADR-0007 = "YAML 프로파일은 연결 정보만, 동작 정책은 base/Java Config" — 일반 원칙
- ADR-0009 (본 task 산출물) = ADR-0007 을 관측성 도메인에 **구체화**. surface 단위로 SSOT 위치를 못 박음. 일반 원칙에서 도출 가능하지만 명시하지 않으면 surface S5/S6 (manifest) 같이 ADR-0007 이 직접 다루지 않은 영역의 결정이 누락됨.
- 따라서 ADR-0009 는 ADR-0007 을 인용하되 supersede 하지 않음. status `Accepted`, 관계는 "Extends ADR-0007".

### ADR-0006 과의 관계

- ADR-0006 = "Monitoring 스택 환경 분리 (`base/monitoring/` 에서 제외, `k8s/monitoring/{minikube,gke,shared}/`)" — manifest 위치 결정
- ADR-0009 은 그 분리된 위치를 **변경하지 않음**. S5 ServiceMonitor 는 `k8s/base/services/peekcart/servicemonitor.yml` (앱 레포지토리 측 SSOT), S6 Grafana alerts 는 `k8s/monitoring/shared/grafana-alerts.yml` (모니터링 스택 측 SSOT) 로 유지하면서, 이 분담 자체를 ADR-0009 가 명시.

### 비대상

- 실제 코드 통합 (예: `ObservabilityConfig` 클래스 신규 생성, `SecurityConfig` 의 actuator 허용 라인을 Java Config 로 이동) — `task-d005-observability-consolidation` 에서
- PromQL 자체 재작성, alert 임계치 변경 — 별도 task
- OpenTelemetry 도입 (Phase 4 후보) — ADR-0009 본문에 forward-compat 메모만 추가, 결정은 별도 ADR
- ServiceMonitor → PodMonitor 전환 같은 manifest 형식 변경
- ADR-0008 (Outbox trace context) 와의 trace surface 통합 — D-005 가 metrics/log/alert 만 다루고, trace 는 별도 surface 군

### 메타 주의

- 본 task 는 D-005 자체를 **해결하지 않는다**. ADR-0009 만 작성. D-005 의 TASKS.md 행은 본 task 종결 후에도 `중간 — 1차 봉합 완료, 잔여 리스크` 유지. 후속 `task-d005-observability-consolidation` 에서 코드 통합이 끝나야 `~~중간~~ 해결됨` 으로 마킹.

## 3. 작업 항목

### Part A — 사전 감사 (ADR Context 입력 정확성 확보)

- [ ] **P1.** 9개 surface (6 family — S1~S5 + S6.a~d) 위치 재확인 (위 §2 표를 단순 복붙하지 않고 실제 파일 라인 인용 형태로 기록)
  - `MetricsConfig.java` 라인 인용 (D-001 봉합 커밋 `715bcfa`)
  - `application.yml` `management.*` 키 라인
  - `application-k8s.yml` 의 `management.endpoint.health.probes.enabled` (회색지대 — ADR-0007 예외 허용 항목)
  - `SecurityConfig.java` actuator 허용 라인
  - `servicemonitor.yml` endpoint 블록
  - `grafana-alerts.yml` alert rule 갯수 + 타입 (P1-E 시점에 `peekcart-target-down` / `peekcart-scrape-absent` / 에러율 / latency)
- [ ] **P2.** 회귀 테스트 (`ObservabilityMetricsIntegrationTest` + `@AutoConfigureObservability`) 의 현 검증 범위 확인
  - **현재 자동 검증**:
    - S1 (histogram bucket 존재 — `_bucket` substring assertion)
    - S2 (`application="peekcart"` 태그 노출)
    - S3 happy path (`/actuator/prometheus` 가 노출되어 200 응답 — exposure whitelist 의 prometheus 항목)
    - S4 happy path (no-auth `restTemplate.getForEntity` 가 200 응답 — permitAll)
  - **미검증 잔여 공백** (ADR 본문에 명시 후 후속 task 에서 어떻게 강제할지 결정):
    - S3 의 정확한 whitelist 형태 (`health` 누락 / 추가 endpoint 노출 등은 검출 불가)
    - S4 의 `/actuator/health/**` 경로 (현 테스트는 prometheus 만 호출)
    - S5 (ServiceMonitor — SpringBoot 테스트 범위 외, k8s integration 필요)
    - S6 PromQL 자체의 입력 series 정합 (Prometheus 미실행 환경에서 검증 불가)

### Part B — Decision 후보 비교

- [ ] **P3.** Decision 후보 3안을 ADR Alternatives 절에 정리
  - **Alt A. Java Code SSOT 단일화** — 모든 코드 표현 가능 surface (S1~S4) 를 단일 `ObservabilityConfig` 클래스로 통합. S5/S6 은 manifest 측 SSOT 유지하되 코드 contract 와 일관 검증
  - **Alt B. Surface별 SSOT 명시 (현 위치 유지)** — 각 surface 의 현 위치를 ADR 표로 못 박고, 변경은 SSOT 위치에서만. 코드 이동 없음
  - **Alt C. 자동 생성** — Java contract → kustomize generator 로 manifest 생성 (heavy, Phase 4 부담)
- [ ] **P4.** 채택안 결정 — **Alt B (현 위치 유지 + SSOT 명시)** 채택. Alt A 의 코드 통합은 후속 task 범위. 본 ADR 의 Decision 절은 surface 별 강제 표(아래 P5 의 6 컬럼) 로 후속 task 작업이 1:1 도출 가능해야 한다.
  - **6 컬럼 강제** (모든 행 필수, "TBD"/"추후 검토" 금지):
    1. Surface (S1~S6.d)
    2. 현 SSOT (파일:라인)
    3. 본 task 변경 (없음 — 모든 행 동일)
    4. Phase 4 owner (모듈/서비스 후보 — 예: `app/observability` 공통 모듈, 또는 각 서비스 own)
    5. 이동·복제 금지 규칙 (예: "S2 application 태그는 base YAML 1개소만, 어떤 환경 프로파일에도 재선언 금지")
    6. 검증 수단 (현재 회귀 테스트 / 수동 확인 / 미검증 — 후속 task 에서 어떻게 격상할지 명시)

### Part C — ADR-0009 작성

- [ ] **P5.** `docs/adr/0009-observability-contract-ssot.md` 신규 — ADR-0007 형식 따름
  - **Status**: 초안 `Proposed`
  - **Date**: 2026-05-04
  - **관련 Phase**: 전체 (Phase 4 진입 전 결정)
  - **Context**: D-005 1차 봉합 후 잔여 리스크 + Phase 4 모듈 분리 동기 + §2 의 9 surface 표
  - **Decision**: surface 별 SSOT 표 + Phase 4 행동 지침 + 회귀 강제 메커니즘
  - **Alternatives Considered**: P3 의 3안
  - **Consequences**: 긍정 (Phase 4 모듈 분리 안전), 부정 (회귀 테스트 격상 비용, S5/S6 자동화 미해결), 후속 (`task-d005-observability-consolidation`)
  - **References**: D-005, ADR-0006, ADR-0007, P1-D 회귀 테스트, D-001 커밋 `715bcfa`
- [ ] **P6.** ADR Status 전환 — 본문 작성 후 별도 커밋으로 `Proposed` → `Accepted`. ADR-0008 패턴과 동일.

### Part D — 인덱스/참조 동기화

- [ ] **P7.** `docs/adr/README.md` INDEX 표 맨 아래 행 추가
  - 행: `| [0009](./0009-observability-contract-ssot.md) | 관측성 계약 SSOT 결정 | Accepted | 전체 | 02, CLAUDE.md |`
- [ ] **P8.** `bash docs/consistency-hints.sh` 실행 후 0 종료 확인 — 본 스크립트는 ADR 참조 파일 존재 여부만 종료 코드로 반영하는 **수동 점검 힌트**. INDEX 행 ↔ 파일 frontmatter 자동 검증은 본 task 비대상 (수동 확인). README INDEX 표 행 추가 후 `0009-observability-contract-ssot.md` 파일 존재 + frontmatter 형식이 ADR-0008 과 동일한지 육안 검증.
- [ ] **P9.** CLAUDE.md `§설정 / YAML 프로파일 규칙` 절 인접에 **`§관측성 계약`** 한 단락 신규 — ADR-0009 요약 1~2 문장 + `(see ADR-0009)` 참조. surface 별 SSOT 표는 ADR 본문에만 두고, CLAUDE.md 는 "어디 보면 됩니까" 만 가리킴.
- [ ] **P10.** `docs/02-architecture.md` 영향 검토 — 본 ADR 이 layer 구성을 바꾸지 않으므로 본문 변경 없음. 단 §관측성 또는 §설정 절이 있다면 `(see ADR-0009)` 1줄 추가 검토. 없으면 P10 은 "확인 후 변경 없음" 으로 종결.

### Part E — 문서 동기화

- [ ] **P11.** `docs/TASKS.md` "완료된 작업" 표에 본 task 행 추가
  - D-005 행 자체는 변경 없음 (해결은 후속 task)
- [ ] **P12.** `docs/progress/PHASE3.md` 본 task 엔트리 추가
  - "Phase 3 잔여 부채 정리 — D-005 ADR 결정" 으로 Phase 3 잔여 작업 종결 흐름 기록

## 4. 영향 파일

| 파일 | 변경 유형 | Part |
|------|-----------|------|
| `docs/adr/0009-observability-contract-ssot.md` | 신규 (Status: Proposed → Accepted) | C (P5, P6) |
| `docs/adr/README.md` | INDEX 표 행 추가 | D (P7) |
| `CLAUDE.md` | `§관측성 계약` 단락 신규 | D (P9) |
| `docs/TASKS.md` | "완료된 작업" 표 행 추가 | E (P11) |
| `docs/progress/PHASE3.md` | 본 task 엔트리 | E (P12) |

코드/매니페스트 변경: **0건**. (`MetricsConfig.java`, `SecurityConfig.java`, `application*.yml`, `servicemonitor.yml`, `grafana-alerts.yml` 모두 본 task 에서는 변경하지 않음 — `task-d005-observability-consolidation` 의 범위.)

## 5. 검증 방법

### 자동

- `bash docs/consistency-hints.sh` exit 0 — ADR 참조 파일 존재 hint 통과
- `./gradlew test` — 코드 변경 0건이므로 244+α 회귀 0건 (확인만)

### 수동 (ADR 본문 품질 — 체크리스트 형식, 모든 항목이 통과해야 ADR Status `Accepted` 전환 가능)

**§Context — 9 surface 표 검증** (S1, S2, S3, S4, S5, S6.a, S6.b, S6.c, S6.d 각 행마다):

- [ ] **C1**: 현 SSOT 컬럼이 실제 파일:라인 형식으로 채워짐 (라인 번호 정확도는 grep/Read 로 재확인)
- [ ] **C2**: 의존 surface 컬럼이 P1#4 정정 결과와 일치 (S1 → latency-only, S6.a → S2+S5, S6.c/d → S5 only 등)
- [ ] **C3**: 변경 시 파급 컬럼이 구체적 (단순 "영향 있음" 금지)

**§Decision — 6 컬럼 surface 표 검증** (P4 의 6 컬럼 모두 채워짐):

- [ ] **D1**: Surface / 현 SSOT (파일:라인) / 본 task 변경 / **Phase 4 owner (모듈명 또는 서비스명 명시, "TBD"/"추후 검토" 금지)** / 이동·복제 금지 규칙 (1문장) / 검증 수단 (회귀 테스트 ID 또는 "수동" 또는 "미검증 → 후속 task action id")
- [ ] **D2**: ADR-0007 (YAML 프로파일 원칙) 와 모순 없음 — 특히 "동작 정책 → Java Config" 가 어느 surface 에 적용/유보되는지 명시
- [ ] **D3**: ADR-0006 (Monitoring 환경 분리) 와 모순 없음 — S5/S6 의 manifest 위치(`base/services/peekcart` vs `monitoring/shared`) 가 본 ADR 에서 변경되지 않음을 명시

**§Alternatives — 3안 비교 동일축 검증**:

- [ ] **A1**: Alt A / B / C 모두 다음 5 비교축으로 채워짐 — 변경 범위, ADR-0007 정합성, Phase 4 비용, 검증 가능성, 채택/기각 사유
- [ ] **A2**: 채택 (Alt B) 의 사유와 기각된 안의 사유가 대칭적 (한쪽만 자세하면 안 됨)

**§Consequences — Phase 4 행동 지침 검증**:

- [ ] **CQ1**: "Phase 4 에서 어느 surface 가 어느 모듈/서비스로 가는가" 질문에 본 ADR 만 보고 surface 단위 답변 도출 가능
- [ ] **CQ2**: 후속 task `task-d005-observability-consolidation` 의 작업 항목이 본 ADR §Consequences 의 6 action 정의 (D5-V1~V6) 와 1:1 로 매칭 가능 (현 모놀리스 검증 action ↔ surface/공백). Phase 4 owner 컬럼 기반 물리적 이동은 CQ1 의 Phase 4 plan 인용 범위로, D-005 consolidation 의 직접 action 으로 끌어오지 않음

### 자동 강제 (회귀 메커니즘)

- 본 task 는 메커니즘 정의만 — 강제 코드 추가는 후속 task 범위
- ADR §Decision 의 surface 표 6번째 컬럼 ("검증 수단") 이 미검증 surface 에 대해 후속 task 의 action id 를 가리켜야 함

## 6. 완료 조건

- [ ] P1 ~ P12 전부 체크
- [ ] ADR-0009 파일 존재 + Status: Accepted
- [ ] `bash docs/consistency-hints.sh` exit 0
- [ ] §5 수동 체크리스트 (C1~C3, D1~D3, A1~A2, CQ1~CQ2) 전부 통과
- [ ] CLAUDE.md `§관측성 계약` 단락 + ADR-0009 참조 존재
- [ ] PR 생성 + 머지

## 7. 트레이드오프 및 결정 근거

| 결정 | 채택 (계획 시점) | 기각 대안 | 근거 |
|------|------|-----------|------|
| Decision 형태 | **Alt B (surface 별 SSOT 명시)** | Alt A 전면, Alt C 자동생성 | Alt A 전면은 본 task 범위 초과(코드 이동 동반) → 후속 task 분리. Alt C 는 Phase 4 진입 전 부담 과다. Alt B + 후속 task 의 코드 통합 = Alt A 와 동등한 종착점이지만 단계 분리로 리스크 ↓ |
| ADR 관계 | "Extends ADR-0007" (Supersede 아님) | Supersede ADR-0007 | ADR-0007 은 일반 원칙으로 유효. ADR-0009 는 도메인 구체화 |
| Phase 4 행동 지침 포함 여부 | 포함 | 별도 ADR 로 분리 | 본 ADR 동기 자체가 Phase 4 모듈 분리 대비. 분리하면 본 ADR 의 의의 약화 |
| 회귀 테스트 격상 명시 | 메커니즘만 명시 (코드 변경 없음) | ADR 에서 코드 변경 강제 | 본 task 는 결정 문서. 강제 구현은 후속 task |
| ADR Status 채택 흐름 | Proposed → 본 task 안에서 Accepted | Proposed 만 + 후속 task 에서 Accepted | ADR-0008 패턴과 동일. 본 task 가 결정 자체이므로 결정 후 Accepted 가 자연스러움 |
| 회색지대 (S3 exposure, S4 Security) SSOT | base YAML(S3) / Java Config(S4) — 현 위치 유지 | S3/S4 모두 단일 Java Config 로 이동 | 이동은 후속 task 의 코드 변경. ADR 은 현 SSOT 를 명시만 |

## 8. 후속 (Out-of-Scope)

- `task-d005-observability-consolidation` — ADR-0009 결정에 따라 코드/manifest 통합. (현재 D-005 가 가리키는 잔여 리스크 1차 봉합 후 작업)
- Phase 4 진입 시 관측성 모듈 분리 — ADR-0009 §Consequences 의 행동 지침대로 모듈 경계 결정
- OpenTelemetry 도입 시 trace surface 추가 — 별도 ADR

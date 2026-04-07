# ADR-0006: Monitoring 스택 환경 분리 (base 에서 제외)

- **Status**: Accepted
- **Decided**: 2026-04-07
- **Documented**: 2026-04-07
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 3 Task 3-4 (부하 테스트) · Phase 4
- **구현 일정**: 본 ADR 은 **설계 결정만** 포함. 실제 파일 재배치/스크립트 변경은 별도 브랜치(`refactor/phase3-monitoring-split` 예정)에서 수행

## Context

ADR-0005 는 `k8s/base/` 를 "환경 무관 공통 매니페스트" 로 규정했습니다. 그러나 ADR-0005 머지 직후 3차 외부 리뷰에서 **base/ 가 실제로는 환경 무관이 아님**이 발견되었습니다.

구체적 사실(현 브랜치 상태 기준):

1. **`k8s/base/monitoring/values-prometheus.yml:1`** 가 파일 첫 줄부터 `# kube-prometheus-stack Helm values — minikube 경량 설정` 으로 선언되어 있고, 본문에 minikube 8GB 환경 전제의 리소스 한도가 하드코딩되어 있음.
   - `prometheus.prometheusSpec.retention: 6h` — minikube 메모리 예산 기준
   - `prometheus` limits: `{ cpu: 500m, memory: 512Mi }` — GKE e2-standard-4 기준으로는 과소
   - `grafana.service.type: NodePort` / `nodePort: 30030` — GKE 에서는 Internal LoadBalancer 가 적합
2. **`k8s/base/monitoring/servicemonitor.yml`** 의 `metadata.namespace` 는 `peekcart` 이지만 디렉토리는 `base/monitoring/` 하위. `base/monitoring/` 의 나머지 리소스는 `monitoring` 네임스페이스 소속이라 **"디렉토리 = 관심사 그룹" 과 "디렉토리 = 네임스페이스" 두 축이 섞여** 독자를 혼란시킴.
3. **`install.sh`** 가 `helm upgrade --install --create-namespace` 로 monitoring 네임스페이스를 생성하고, base 가 이 네임스페이스에 종속된 ConfigMap/Alert 를 포함. fresh 클러스터에서 `kubectl apply -k overlays/minikube/` 를 단독 실행하면 `namespace "monitoring" not found` 로 실패함 → Kustomize 의 "선언적 self-contained deployment" 가치 훼손.

ADR-0005 §부정적 영향 은 이 중 3번만 기록했고 1·2 번은 놓쳤습니다. 즉 **ADR-0005 의 Decision 은 app/infra 리소스에는 유효하지만 monitoring 스택에는 부분적으로 무효**입니다.

현재 gke overlay 는 placeholder 이므로 "monitoring 도 환경 분리가 필요하다" 는 사실이 문제를 일으키기 전에 지금 결정해두는 것이 Task 3-4 Step 0 의 범위를 명확히 합니다.

## Decision

**Monitoring 스택(Helm values + Grafana 대시보드/Alert + ServiceMonitor) 을 Kustomize base/ 에서 완전히 분리**한다.

본 ADR 은 **결정(Decision) 과 불변식(Invariants) 만** 확정하고, 실제 디렉토리 구조와 파일 이동은 구현 PR 에서 다음 원칙 하에 finalize 한다.

### 불변식 (구현 PR 이 반드시 지킬 원칙)

1. **base/ 에는 monitoring 네임스페이스에 속한 리소스가 존재하지 않는다.** Grafana 대시보드 ConfigMap, PrometheusRule, kube-prometheus-stack Helm values 는 base 가 아니다.
2. **`peekcart` 네임스페이스의 ServiceMonitor 는 `base/services/peekcart/` 로 이동**한다. 관심사("앱이 자신의 메트릭을 노출하는 방법") 는 서비스 소속이므로 디렉토리도 서비스를 따른다.
3. **Helm values 는 환경별 파일** (`{minikube,gke}` overlay 하위 또는 별도 `k8s/monitoring/{minikube,gke}/` 트리) 로 분리된다. minikube 값과 GKE 값은 **동일 파일이 될 수 없다** (retention, 리소스 limits, Service type 이 모두 다름).
4. **`install.sh` 는 환경 중립이거나, 환경별 install 진입점이 분리된다.** `--create-namespace` 옵션의 위치는 구현 PR 에서 결정하되, `kubectl apply -k overlays/<env>/` 단독 실행이 drift 를 일으키지 않아야 한다.
5. **base/namespace.yml 에 `monitoring` Namespace 리소스가 추가**되거나, 또는 monitoring 네임스페이스 생성 주체가 명시적으로 단일화된다. 현재처럼 "Helm 이 생성한 네임스페이스를 Kustomize 가 재사용" 하는 순환 의존은 허용되지 않는다.
6. **GKE overlay 는 Task 3-4 Step 0 에서 자체 monitoring values 를 작성**해야 한다. base 가 monitoring 을 포함하지 않으므로, GKE overlay 의 monitoring 누락 = 의도된 미구현 상태 (placeholder 가 아니라 TODO 가 명시됨).

### 본 ADR 이 결정하지 않는 것

- 정확한 디렉토리 배치 (`overlays/<env>/monitoring/` vs `k8s/monitoring/<env>/` vs `charts/` 등) — 구현 PR 에서 실제 파일을 이동해 보며 결정
- install.sh 파라미터화 방식 (env 인자 vs 별도 스크립트 vs Makefile 타깃)
- 기존 minikube monitoring 설정의 물리적 위치 (파일명/경로 변경 여부)

## Alternatives Considered

### Alternative A: `values-prometheus.yml` 한 파일만 overlay 로 이동
- **장점**: 최소 변경. 리뷰 부담 적음
- **단점**:
  - ServiceMonitor 의 `peekcart` NS 불일치는 해결 안 됨
  - install.sh 순환 의존 (Helm 이 NS 생성 → Kustomize 가 재사용) 그대로
  - base/monitoring/dashboards, alerts 가 여전히 monitoring NS 라 "base = 환경 무관 + 단일 NS" 가 깨진 채 유지
- **기각 사유**: 리뷰에서 지적된 세 문제 중 한 가지만 해결. 나머지 두 문제가 Phase 4 에서 더 큰 drift 를 유발

### Alternative B: Monitoring 전체를 base 에서 분리 (본 ADR 채택안)
- **장점**:
  - base/ 의 "환경 무관" 불변식이 실제로 성립
  - `kubectl apply -k overlays/<env>/` 가 self-contained 가 됨 (ADR-0006 불변식 4·5)
  - ServiceMonitor 가 `base/services/peekcart/` 로 이동하여 관심사 경계가 깔끔
  - GKE overlay 의 monitoring 가 "placeholder 인 척하는 TODO" 에서 "명시된 TODO" 로 승격 — 오독 여지 차단
- **단점**:
  - Task 3-4 Step 0 에 "GKE monitoring values 작성" 이 추가됨 (Phase 3 부하 테스트 착수 전 선행 작업 증가)
  - install.sh 가 환경별로 분화되거나 파라미터를 받아야 함
- **채택 사유**: 리뷰 지적 세 가지 모두 해결하며, ADR-0005 의 의도였던 "base/overlays 경계" 를 실제로 달성

### Alternative C: Monitoring 스택 전체를 Kustomize 밖으로 (`k8s/monitoring/` 최상위 디렉토리)
- **장점**: Helm 과 Kustomize 의 경계가 디렉토리 수준에서 명확
- **단점**:
  - `k8s/` 아래 두 도구 트리가 병렬로 존재 — 02-architecture.md 의 구조 설명 복잡도 증가
  - Phase 4 MSA 전환 시 서비스별 ServiceMonitor 를 어디에 둘지 다시 결정해야 함 (Alternative B 는 `base/services/<svc>/` 패턴으로 자연 확장)
- **기각 사유**: 당장 단순해 보이지만 Phase 4 에서 비용 회귀

## Consequences

### 긍정적 영향
- **base/overlays 경계가 실제로 성립** — ADR-0005 의 의도가 monitoring 영역까지 확장됨
- **`kubectl apply -k overlays/minikube/` 가 self-contained** (install.sh 선행 의존 해소 또는 명시적 순서 고정)
- **ServiceMonitor 위치가 관심사(서비스 소속) 와 일치** — 디렉토리 의미론 일관
- **GKE overlay 의 "완성도 과장" 리스크 제거** — monitoring 누락이 placeholder 에서 TODO 로 승격, Task 3-4 Step 0 에서 작성 강제
- **Phase 4 서비스 추가 시 ServiceMonitor 배치가 자연스러움** — `base/services/<service>/servicemonitor.yml` 패턴

### 부정적 영향 / 트레이드오프
- **Task 3-4 Step 0 에 작업 추가** — GKE 용 Helm values, install 경로, Service 노출 방식 결정이 부하 테스트 착수 전 선행 필요
- **install.sh 단순성 저하** — 파라미터화 또는 분기 진입점 필요
- **문서 개정 비용** — ADR-0005 의 monitoring 관련 서술이 Partially Superseded, 02-architecture.md §12 의 배포 절차 갱신 필요
- **구현 PR 의 diff 가 커짐** — 파일 이동 + install.sh 수정 + overlay 신규 파일. 본 ADR 이 "설계만" 이고 구현을 분리한 이유

### 후속 결정에 미치는 영향
- **ADR-0005 → `Partially Superseded by ADR-0006` (monitoring 범위만)** — app/infra 리소스에 대한 Decision 은 유지, monitoring 관련 Decision/Consequences 는 본 ADR 이 대체
- **Task 3-4 Step 0** 체크리스트에 "GKE monitoring values 작성" 추가 (Artifact Registry 이미지 경로, StorageClass 등과 동등한 선행 작업)
- **`docs/02-architecture.md` §12 배포 절차** 가 환경별로 분화 (minikube 와 GKE 가 같은 install 절차를 공유하지 않음)
- **구현 브랜치** (`refactor/phase3-monitoring-split` 가칭) 가 본 ADR 을 구현하는 즉시 본 ADR Status 는 `Accepted` 로 전환

## 구현 브랜치 체크리스트 (참고용, 비규범)

본 ADR 을 구현하는 브랜치는 최소 다음을 포함해야 합니다. 상세 설계는 구현 PR 에서 확정합니다.

- [ ] `base/monitoring/` 디렉토리 해체 — 파일별 이동 대상 결정
- [ ] `servicemonitor.yml` → `base/services/peekcart/` 로 이동
- [ ] `values-prometheus.yml` 을 환경별로 분리 (minikube / gke 각 1개)
- [ ] `install.sh` 파라미터화 또는 환경별 진입점 분리
- [ ] `base/namespace.yml` 에 `monitoring` Namespace 추가 여부 결정 (불변식 5)
- [ ] `overlays/minikube/kustomization.yml` / `overlays/gke/kustomization.yml` 에 monitoring 리소스 경로 포함
- [ ] `kubectl apply -k overlays/minikube/` 단독 실행 성공 검증 (fresh 클러스터)
- [ ] `docs/02-architecture.md` §12 배포 절차 갱신
- [ ] ADR-0006 Status 를 `Proposed` → `Accepted` 로 전환
- [ ] ADR-0005 Status 는 `Partially Superseded by ADR-0006` 유지 (본 ADR 머지 시점부터)

## References
- ADR-0005 — 본 ADR 이 부분 supersede (monitoring 범위)
- `k8s/base/monitoring/values-prometheus.yml:1` — minikube 전용 설정이 base 에 존재하는 근거
- `k8s/base/monitoring/servicemonitor.yml` — peekcart NS / monitoring 디렉토리 불일치
- `docs/02-architecture.md` §12 — 현 배포 절차 (갱신 예정)
- `docs/progress/PHASE3.md` — 3차 외부 리뷰 기록 (ADR-0006 트리거)
- 선행: ADR-0005

## Update Log

- **2026-04-07** — 구현 브랜치(`refactor/phase3-monitoring-split`) 작업 중 §Consequences 의 *"`kubectl apply -k overlays/minikube/` 가 self-contained"* 표현이 사실과 다름을 발견. ServiceMonitor 가 `monitoring.coreos.com/v1` CRD 에 의존하므로 fresh 클러스터에서 overlay 단독 적용은 불가능. 본 ADR 의 의도는 *"overlay 가 monitoring NS 리소스를 만들거나 외부 상태를 변형하지 않는다"* 였으며, CRD 선행 의존은 K8s 생태계 표준 패턴(cert-manager, Istio, ArgoCD 등)과 동일. 운영 해석은 `docs/02-architecture.md §12` 의 4단계 배포 순서 + "self-contained overlay 의 운영 해석" 노트로 위임. 본 항목은 사실 정정 (객관적 CRD 의존 발견) 이며 트레이드오프 재해석이 아님. 관련 커밋: `c28ba26`.

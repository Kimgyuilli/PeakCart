# task-hpa-manifest — HPA 매니페스트 작성

> 작성: 2026-04-21
> 관련 Phase: Phase 3 — Task 3-5 (HPA 검증) 선행 작업
> 관련 ADR: ADR-0004, ADR-0005, ADR-0006

## 1. 목표

`Task 3-5: HPA 검증`(Phase 3 Exit Criteria 항목)의 선행 작업으로, 현재 모놀리스 Deployment `peekcart` 를 대상으로 하는 HPA 매니페스트를 작성한다.
목표는 CPU 기반 자동 스케일아웃 정책을 Kustomize 구조에 맞게 추가하고, 이후 부하 테스트에서 실제 scale 이벤트를 관측할 수 있는 기준 구성을 확보하는 것이다.

> **대상 워크로드 용어**: 본 계획서 내 HPA 대상은 단일 모놀리스 Deployment `peekcart` (`k8s/base/services/peekcart/`) 다. `docs/TASKS.md` Task 3-5 와 `docs/07-roadmap-portfolio.md` 에 남아있는 `Order Service HPA` 표기는 Phase 4 MSA 분리 이후의 명칭 잔재이며, 본 task 범위에서는 `peekcart` 로 통일한다 (TASKS/07 용어 정리는 P5 에서 수행).

## 2. 배경 / 제약

- 현재 `docs/TASKS.md` 기준으로 `Task 3-5`는 미완료 상태다.
- HPA 검증은 GKE 운영 경로를 전제로 하지만, 본 task는 우선 **매니페스트 작성과 정적 검증**에 한정한다.
- 기존 Kustomize 구조 (`k8s/base` + `k8s/overlays/{minikube,gke}`) 및 monitoring 분리 원칙을 깨면 안 된다.
- metrics-server 자체 설치 여부는 GKE 기본 제공 전제를 우선 사용하고, 별도 설치 작업은 범위 밖으로 둔다.

## 3. 작업 항목

- [ ] **P1.** HPA 리소스를 `k8s/overlays/gke/` 에 배치 (결정 완료)
  - 근거 1 — ADR-0005 는 `k8s/base` 를 "환경 무관 공통 매니페스트" 로 정의한다. HPA 정책은 환경별 metrics-server 전제 + 리소스 정책에 묶이므로 base 에 두면 원칙 충돌
  - 근거 2 — ADR-0004 는 minikube 8GB 환경에서 `maxReplicas=3` 이 eviction 위험을 유발한다고 명시. 실제로 `k8s/overlays/gke/patches/peekcart-deployment.yml` 최상단 주석은 `HPA max=3` 전제를 기준으로 GKE resources 상향 근거를 기술하고 있어, GKE 전용 배치가 이미 코드 레벨에서 정합
  - 적용 범위: minikube overlay 는 본 task 대상 아님 (매니페스트 없음 = 렌더링 출력에 HPA 미포함이 정상). GKE overlay 렌더링 결과에만 HPA 포함
- [ ] **P2.** `peekcart` 대상 HPA 매니페스트 작성
  - `apiVersion: autoscaling/v2`
  - `minReplicas: 1`
  - `maxReplicas: 3`
  - CPU 평균 사용률 기준 target 포함
- [ ] **P3.** Kustomize 연결
  - `k8s/overlays/gke/kustomization.yml` 의 `resources:` 에 HPA 파일 추가
  - 기존 deployment/service/monitoring 경로와 충돌 없는지 확인
- [ ] **P4.** 정적 + 서버측 검증
  - `kubectl kustomize k8s/overlays/gke` → 렌더링 출력에 `kind: HorizontalPodAutoscaler` 포함 확인
  - `kubectl kustomize k8s/overlays/minikube` → 회귀 없음 (HPA 미포함이 정상)
  - `kubectl apply --dry-run=server -k k8s/overlays/gke` → HPA API 스키마 수용 + `scaleTargetRef` 대상(Deployment `peekcart`) 참조 유효성 확인
  - 런타임 metrics API 확인(`kubectl top pods`) 및 실제 scale-out 관측은 Task 3-5 본편으로 이관 (본 task 비대상)
- [ ] **P5.** Layer 1 문서 갱신
  - `docs/02-architecture.md` §12 K8s 트리에 `k8s/overlays/gke/hpa.yml` 반영
  - `k8s/overlays/gke/README.md` 배포 순서 섹션에 HPA 적용 단계 추가 (필요 시)
  - `docs/TASKS.md` Task 3-5 항목 비고 및 `docs/07-roadmap-portfolio.md` 의 `Order Service` 잔재 용어 → `peekcart` 로 정리 (본 계획서 §1 용어 결정 반영)
  - 런타임 검증(부하 중 scale-out, Grafana 스크린샷)은 후속 범위임을 명시

## 4. 영향 파일

| 파일 | 종류 | 변경 요지 |
|------|------|----------|
| `k8s/overlays/gke/hpa.yml` | 신규 | HPA (autoscaling/v2, peekcart Deployment 대상) 추가 |
| `k8s/overlays/gke/kustomization.yml` | 수정 | `resources:` 에 `hpa.yml` 추가 |
| `docs/02-architecture.md` §12 | 수정 | K8s 트리 그림에 `overlays/gke/hpa.yml` 반영 |
| `k8s/overlays/gke/README.md` | 수정 | 배포 순서에 HPA 적용 단계 명시 (필요 시) |
| `docs/TASKS.md` Task 3-5 / `docs/07-roadmap-portfolio.md` | 수정 | `Order Service` 용어 잔재 정리 및 metrics-server 환경별 전제 비고 동기화 |

## 5. 검증 방법

- 렌더링 검증:
  - `kubectl kustomize k8s/overlays/gke` → 출력에 `kind: HorizontalPodAutoscaler` 포함 확인
  - `kubectl kustomize k8s/overlays/minikube` → 회귀 없음 (HPA 미포함이 정상)
- 서버측 검증:
  - `kubectl apply --dry-run=server -k k8s/overlays/gke` → HPA API 스키마 수용 + `scaleTargetRef` 가 Deployment `peekcart` 를 유효하게 참조
- 구조 검증:
  - monitoring/alert 리소스와 직접 결합되지 않는지 확인 (ADR-0006 원칙 유지)
- 환경별 전제:

  | 환경 | HPA 매니페스트 포함 | metrics-server | 본 task 검증 범위 |
  |------|--------------------|----------------|-------------------|
  | GKE (`overlays/gke`) | 포함 | 기본 제공 (Cloud Monitoring 연계) | 정적 + `apply --dry-run=server` |
  | minikube (`overlays/minikube`) | 비포함 | 본 task 에서는 addon 설치 불요 | 렌더링 회귀 확인만 |

- 범위 검증:
  - 런타임 부하 테스트, 실제 scale 이벤트, Grafana 스크린샷, `kubectl top pods` 실측은 본 task 의 완료 조건이 아님 (후속 Task 3-5 본편 범위)

## 6. 완료 조건

- HPA 매니페스트와 Kustomize 연결이 완료됨
- 정적 렌더링 검증이 통과함
- 후속 부하/HPA 실측에 필요한 최소 구성이 준비됨

## 7. 트레이드오프 / 비대상

- 본 task는 **실측 준비** 단계다. 실제 scale-out 검증은 후속 부하 테스트가 필요하다.
- metrics-server 설치나 GKE 클러스터 운영 변경은 별도 운영 작업으로 남긴다.
- Grafana 스크린샷, 캐싱 TPS 비교, Kafka Lag 측정은 본 task 범위에 포함하지 않는다.

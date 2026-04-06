# ADR-0005: Kustomize base/overlays 매니페스트 구조

- **Status**: Accepted
- **Date**: 2026-04-06
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 3 (부하 테스트 이후) · Phase 4

## Context

ADR-0004 에서 Phase 3 부하 테스트부터 운영 환경을 GCP/GKE 로 전환하기로 결정했습니다. 동시에 ADR-0003 에 따라 **Phase 3 Task 3-1~3-3 의 minikube 환경은 로컬 개발용으로 보존**해야 합니다 (Phase 1·2 는 Docker Compose 였으며 K8s 매니페스트가 존재하지 않습니다). 또한 ADR-0002 의 Phase 4 MSA 전환 시 **서비스별 매니페스트 디렉토리**가 추가될 예정입니다.

현재 `k8s/` 구조:

```
k8s/
├── namespace.yml
├── app/peekcart-deployment.yml  # minikube 가정: imagePullPolicy: Never, NodePort
├── infra/{mysql,redis,kafka}-deployment.yml
└── monitoring/{values-prometheus.yml, servicemonitor.yml, dashboards/, alerts/}
```

이 구조는 다음 요구를 동시에 만족하지 못합니다.

1. **동일 매니페스트로 minikube / GKE 양쪽 배포 가능** — 지금은 환경 가정이 본문에 박힘
2. **환경 차이를 명시적으로 분리** — patch 가 흩어지면 환경별 재현성 떨어짐
3. **Phase 4 서비스 추가가 기존 파일 수정이 아닌 파일 추가 로 이뤄짐** — 파일 수정 기반은 리뷰/롤백 비용 큼
4. **kube-prometheus-stack (Helm) 과 공존** — 관측성 스택은 Helm, 앱/인프라는 Kustomize

## Decision

**Kustomize 의 `base/overlays/` 패턴**을 채택하고, 매니페스트를 다음 구조로 재배치한다.

```
k8s/
├── base/                            # 환경 무관 공통 매니페스트
│   ├── namespace.yml
│   ├── infra/                       # Phase 3 단순화: 디렉토리당 단일 매니페스트 파일
│   │   ├── mysql/mysql.yml          # Deployment + Service + PVC 통합
│   │   ├── redis/redis.yml
│   │   └── kafka/kafka.yml
│   ├── monitoring/                  # kube-prometheus-stack values + 추가 리소스
│   │   ├── values-prometheus.yml    # Helm values (Kustomize 대상 아님, install.sh 가 소비)
│   │   ├── servicemonitor.yml       # Phase 3 시점은 monitoring 하위에 위치
│   │   ├── dashboards/configmap.yml
│   │   ├── alerts/grafana-alerts.yml
│   │   └── install.sh
│   ├── services/
│   │   └── peekcart/                # Phase 3: 모놀리스 단일 서비스
│   │       ├── deployment.yml       # Deployment + Service 통합 (환경 비종속)
│   │       ├── configmap.yml
│   │       └── secret.yml
│   └── kustomization.yml            # base 전체를 한 곳에서 집계 (per-디렉토리 kustomization 미사용)
└── overlays/
    ├── minikube/                    # Phase 3 Task 3-1~3-3 로컬 검증용 (ADR-0003)
    │   ├── kustomization.yml
    │   └── patches/                 # imagePullPolicy: Never, Service type: NodePort 등
    └── gke/                         # Phase 3 부하 테스트 + Phase 4 운영 (ADR-0004)
        ├── kustomization.yml        # 현재는 placeholder, Task 3-4 Step 0 에서 patches 추가
        └── patches/                 # storageClassName, Internal LoadBalancer, 리소스 조정 (예정)
```

- **kube-prometheus-stack 은 Helm 그대로** — Kustomize 로 변환 시도하지 않음. install.sh 가 `helm upgrade --install` 로 idempotent 하게 소비
- **Phase 4 서비스 추가 시**: `base/services/` 하위에 `order-service/`, `payment-service/` 등 형제 디렉토리 추가 + `base/kustomization.yml` 에 resource 경로 한 줄 추가. 기존 파일 수정 없음
- **Phase 3 시점에는 `services/peekcart/` 디렉토리 한 단계만** 추가. `gateway/`, `order-service/` 등 미래 디렉토리를 미리 만들지 않음 (CLAUDE.md §2 Simplicity First)
- **`base/kustomization.yml` 에 `namespace:` 필드를 사용하지 않는다**. base 가 두 개의 네임스페이스(`peekcart` 도메인 리소스 + `monitoring` Grafana ConfigMap/Alert) 에 걸쳐 있고, Kustomize 의 `namespace:` 필드는 모든 리소스를 한 네임스페이스로 강제 변환하기 때문이다. 각 매니페스트가 `metadata.namespace` 를 명시적으로 선언하는 방식으로 회피한다 (대안: 매니페스트를 NS 별로 분리한 base 두 개 구성 — Phase 3 규모에서 과함).

## Alternatives Considered

### Alternative A: Helm chart 로 통일 (kube-prometheus-stack 과 동일 도구)
- **장점**: 도구 단일화. 템플릿 엔진 강력
- **단점**:
  - Helm 템플릿 언어 학습 곡선
  - Phase 3 범위에서 환경 차이가 patch 3~4개 수준 — Go template 은 과함
  - 포트폴리오 설명 비용 증가 (차트 구조 설명)
- **기각 사유**: "환경별 patch" 라는 단순한 요구에 Kustomize 가 최소 복잡도로 맞음

### Alternative B: 환경별 디렉토리 복제 (`k8s/minikube/`, `k8s/gke/` 각각 전체 매니페스트)
- **장점**: 가장 이해하기 쉬움. 도구 없이 `kubectl apply -f k8s/gke/` 한 줄
- **단점**:
  - 공통 부분 수정 시 두 곳을 동기화해야 함 → drift 발생 거의 확실
  - Phase 4 서비스 추가 시 drift 비용 폭증 (서비스 × 환경)
- **기각 사유**: drift 리스크가 포트폴리오 품질을 해침

### Alternative C: 단일 매니페스트 + 환경변수 주입 (envsubst 등)
- **장점**: 가장 단순
- **단점**: patch 수준의 차이(Service type, imagePullPolicy 등) 를 환경변수로 치환하려면 템플릿화가 과도해짐. Kustomize 의 strategic merge 수준에 도달 불가
- **기각 사유**: patch 표현력 부족

### Alternative D: 현재 구조 유지 + 환경별 README 로 수동 안내
- **장점**: 변경 없음
- **단점**: "동일 매니페스트로 양쪽 배포" 요구 자체를 포기. ADR-0003·0004 의 이원화를 매니페스트 레벨에서 반영하지 못함
- **기각 사유**: 요구사항 미충족

## Consequences

### 긍정적 영향
- minikube 배포 (검증 완료): `bash k8s/base/monitoring/install.sh` 로 monitoring 네임스페이스 생성 후 `kubectl apply -k k8s/overlays/minikube/`. 순서 의존성은 `docs/02-architecture.md` §12 배포 절 참고
- GKE 배포: **현재 `k8s/overlays/gke/` 는 placeholder 이며 deploy-ready 가 아니다.** Task 3-4 Step 0 에서 Artifact Registry 이미지 경로, Service 노출 방식(Internal LB), StorageClass(`standard-rwo`), resources requests/limits 상향, secret 분리 전략을 추가한 후에만 적용 가능. 그 전에 `kubectl apply -k k8s/overlays/gke/` 를 실행하면 base 의 GHCR 이미지/ClusterIP/minikube 튜닝이 그대로 적용되어 imagePullBackoff 등으로 실패한다
- 환경 차이가 `overlays/*/patches/` 에 국소화되어 리뷰/롤백 단위 명확
- Phase 4 서비스 추가가 **파일 추가**로 완료 (기존 파일 수정 없음) → PR 리뷰 단순
- `kubectl kustomize` 로 dry-run 검증 가능 → check-consistency.sh 에 통합 가능

### 부정적 영향 / 트레이드오프
- Kustomize 학습 필요 (단, Helm 대비 훨씬 낮음)
- 디렉토리 깊이 증가 — `k8s/base/services/peekcart/deployment.yml` 은 기존 `k8s/app/peekcart-deployment.yml` 보다 한 단계 깊음
- kube-prometheus-stack 만 Helm으로 남아 **도구 혼재** — 단, install.sh 가 이 복잡성을 감춤
- `namespace:` 필드 미사용으로 인해 **신규 리소스에서 `metadata.namespace` 누락 시 default NS 로 새는 위험**. Kustomize 의 안전망 없음. 현 base 에 PR 추가 시 리뷰어가 명시적으로 확인해야 함 (자동 lint 미도입)
- base 가 monitoring 네임스페이스 리소스를 포함하지만 namespace 자체는 `install.sh` 의 `helm upgrade --install --create-namespace` 에 의존 → **fresh 클러스터에서 install.sh 선행 필수** (apply -k 만 먼저 치면 `namespace "monitoring" not found` 로 실패)

### 후속 결정에 미치는 영향
- Phase 4 MSA 전환 시 본 구조가 **서비스별 디렉토리 추가 패턴**의 기반이 됨
- check-consistency.sh 에 `kubectl kustomize ... --dry-run` 검증 단계 추가 고려 (선택)

## References
- `docs/02-architecture.md` — k8s 디렉토리 구조 (본 ADR 반영 후 갱신 예정)
- Kustomize 공식: https://kubectl.docs.kubernetes.io/guides/introduction/kustomize/
- 선행: ADR-0003, ADR-0004

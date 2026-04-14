# GKE Overlay

Phase 3 Task 3-4 부하 테스트 및 Phase 4 운영 환경용 Kustomize overlay.
근거: ADR-0004 (GKE 전환), ADR-0005 (Kustomize 구조), ADR-0006 (monitoring 분리).

## 전제

- GKE Standard 클러스터, `asia-northeast3-a`, `e2-standard-4 × 1`
- Artifact Registry 리포지토리: `asia-northeast3-docker.pkg.dev/<PROJECT_ID>/peekcart`
- 부하 발생기는 같은 VPC 의 별도 Compute Engine VM (ADR-0004)

## 이미지 경로 치환 (PROJECT_ID)

커밋된 `kustomization.yml` 의 `images:` 는 `PROJECT_ID_PLACEHOLDER` 를 사용합니다.
apply 전에 로컬에서 치환하되 **커밋하지 마세요** (operator 로컬 상태).

```bash
cd k8s/overlays/gke
kustomize edit set image \
  ghcr.io/kimgyuilli/peakcart=asia-northeast3-docker.pkg.dev/<YOUR_PROJECT>/peekcart/peekcart:latest

# 렌더링 확인
kubectl kustomize .

# apply 후 반드시 원복
git restore kustomization.yml
```

## 이미지 운반 (일회성 수동 push)

CI 는 GHCR 로 push 합니다 (`.github/workflows/ci.yml`). Phase 3 부하 테스트는 측정 빈도가 낮아 CI 자동화 대신 수동 복사를 사용합니다.

```bash
# Artifact Registry 인증
gcloud auth configure-docker asia-northeast3-docker.pkg.dev

# 리포지토리 생성 (최초 1회)
gcloud artifacts repositories create peekcart \
  --repository-format=docker \
  --location=asia-northeast3

# GHCR → Artifact Registry 복사 (crane 권장, 또는 docker pull/tag/push)
crane copy \
  ghcr.io/kimgyuilli/peakcart:latest \
  asia-northeast3-docker.pkg.dev/<YOUR_PROJECT>/peekcart/peekcart:latest
```

## 배포 순서

`docs/02-architecture.md §12` 의 GKE 배포 순서를 따릅니다.
ServiceMonitor CRD 선행 의존이 있으므로 monitoring 스택을 먼저 설치해야 합니다.

> **중요**: 아래 4단계는 **모두** 실행해야 부하 테스트 환경이 완성됩니다.
> `kubectl apply -k overlays/gke/` 단독 실행은 monitoring 스택을 포함하지 않습니다 (ADR-0006 불변식 1·4).
> 3단계(shared 대시보드/Alert) 를 건너뛰면 Grafana 가 비어 있는 상태로 뜨니 주의.

```bash
# 1. monitoring NS
kubectl apply -f k8s/monitoring/namespace.yml

# 2. kube-prometheus-stack (ServiceMonitor CRD 등록)
bash k8s/monitoring/gke/install.sh

# 3. 환경 무관 대시보드/Alert (configMapGenerator 가 *.json → ConfigMap 생성)
kubectl apply -k k8s/monitoring/shared/

# 4. app/infra + ServiceMonitor
kubectl apply -k k8s/overlays/gke/
```

## 정리 (ADR-0004 운영 체크리스트)

측정 종료 시 반드시 실행:

```bash
gcloud container clusters delete peekcart-loadtest --region=asia-northeast3-a
gcloud compute instances delete loadgen --zone=asia-northeast3-a
gcloud compute disks list         # orphan PD 확인
gcloud compute addresses list     # 예약 IP 확인
```

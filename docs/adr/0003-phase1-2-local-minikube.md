# ADR-0003: Phase 1·2 로컬 minikube 환경 채택

- **Status**: Superseded by ADR-0004
- **Date**: 2026-01-15 (retroactive — Phase 0 설계 시점)
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 1·2 (완료), Phase 3 로컬 개발 용도(유지)

## Context

Phase 1·2는 비즈니스 로직과 Kafka/Outbox/DLQ 등 성능 개선 요소 구현에 집중하는 단계입니다. 이 시점에서는 다음 제약이 있었습니다.

- **비용 최소화** — 포트폴리오 프로젝트의 현실적 예산 제약 (최초 계획 단계에서 클라우드 크레딧 미확보)
- **빠른 반복** — 로컬에서 코드 변경 → 빌드 → 배포 → 검증 사이클이 짧아야 함
- **오프라인 개발 가능성** — 외부 의존 없이 재현 가능한 환경
- **Phase 1·2의 주요 관심사는 코드 품질** — 부하 테스트/정확한 성능 측정은 Phase 3의 과제

## Decision

Phase 1·2 구현 및 검증 환경으로 **로컬 minikube**를 채택한다.

- minikube CPU 4코어 / Memory 8GB
- `k8s/` 하위 매니페스트는 minikube 환경을 기본 가정
- 인프라(MySQL/Redis/Kafka)는 모두 클러스터 내부에 배포
- 관측성 스택(kube-prometheus-stack)도 동일 클러스터에 배치

## Alternatives Considered

### Alternative A: Docker Compose만 사용 (K8s 생략)
- **장점**: 가장 단순, 러닝 커브 낮음
- **단점**: Phase 3에서 도입할 K8s 관측성/HPA/Probe를 Phase 1·2에서 전혀 경험하지 못함
- **기각 사유**: Phase 3 전환 시 러닝 커브가 한꺼번에 몰림. 로컬에서도 K8s 학습 필요

### Alternative B: 처음부터 클라우드 K8s (GKE/EKS)
- **장점**: 프로덕션에 가까운 환경. Phase 3 전환 비용 없음
- **단점**:
  - Phase 1·2 개발 중 지속적으로 비용 발생
  - 네트워크 왕복으로 인한 반복 사이클 저하
  - 당시 클라우드 크레딧 미확보 상태
- **기각 사유**: 비용/속도 모두 Phase 1·2의 목적(코드 품질)에 역행

### Alternative C: kind (Kubernetes in Docker)
- **장점**: minikube보다 기동 빠름, 멀티노드 구성 용이
- **단점**: Docker Desktop 이외 환경에서 운영 복잡. Windows 사용자 온보딩 비용
- **기각 사유**: macOS 단독 개발 환경에서 minikube 대비 차별점 미미. minikube의 Ingress/Addon 생태계가 더 풍부

## Consequences

### 긍정적 영향
- Phase 1·2 개발 중 제로 비용
- 인터넷 없이도 전체 스택 재현 가능 → 재현성 확보
- Phase 3의 K8s 기본기(매니페스트, Probe, Service)를 Phase 1·2에서 자연스럽게 학습
- `k8s/overlays/minikube/` 구조는 Phase 3 이후에도 **로컬 개발용 검증 환경**으로 계속 유효

### 부정적 영향 / 트레이드오프
- **부하 테스트의 정확도 한계** — 8GB 메모리 안에 앱/인프라/관측성/부하 도구가 공존하면 부하 도구 자체가 병목이 되어 측정값 왜곡 발생 가능
- **Prometheus/HPA 동작 여유 부족** — HPA max=3 + Prometheus 시계열 누적 시 OOM eviction 위험
- 위 한계는 Phase 3 부하 테스트 단계에서 명시적으로 드러났으며, **ADR-0004에서 Phase 3 운영 환경을 GCP/GKE로 전환하는 직접적 근거**가 되었습니다.

### 후속 결정에 미치는 영향
- ADR-0004 (GCP 전환): 본 ADR의 한계가 전환의 직접 근거
- ADR-0005 (Kustomize 구조): minikube 환경을 `overlays/minikube/`로 보존하여 로컬 개발에서 계속 사용 가능하게 설계

## References
- `docs/04-design-deep-dive.md` Section 10-7 (원문) — 로컬 minikube 측정 환경 맥락
- `docs/02-architecture.md` Section 4-3 (원문) — Kubernetes (minikube) 인프라 전략
- `docs/progress/PHASE1.md`, `docs/progress/PHASE2.md` — 실제 Phase 1·2 작업 이력
- 후속: ADR-0004

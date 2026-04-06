# ADR-0003: Phase 3 초기 K8s 환경 — 로컬 minikube 채택

- **Status**: Partially superseded by ADR-0004
  - Task 3-1 ~ 3-3 (CI, K8s 최초 배포, 관측성 스택 초기 검증): 본 ADR 유지, 완료됨
  - Task 3-4 ~ (부하 테스트, HPA, Phase 4 운영): ADR-0004 로 전환
- **Date**: 2026-01-15 (retroactive — Phase 0 초기 설계 시점)
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 3 (Task 3-1 ~ 3-3)

## Context

Phase 1·2 는 Docker Compose 로 MySQL/Redis/Kafka 를 띄우고 Spring Boot 를 로컬 실행하는 구조였습니다(`docker-compose.yml`). K8s 는 Phase 3 부터 도입할 계획이었고, 본 ADR 은 **Phase 3 에서 K8s 를 처음 도입할 때의 초기 환경 선택**을 기록합니다.

Phase 3 의 주된 관심사는 CI, K8s 매니페스트 작성, 관측성 스택(kube-prometheus-stack) 구축, 부하 테스트, HPA 검증입니다. 이 중 부하 테스트/HPA 는 정확한 측정이 핵심이지만, CI·매니페스트·관측성 구축 단계는 **반복 검증 사이클의 속도**와 **비용 0** 이 더 중요합니다.

초기 설계 시점(Phase 0)의 제약:

- **비용 최소화** — 당시 클라우드 크레딧 미확보
- **빠른 반복** — K8s 매니페스트 시행착오는 빠른 rebuild → apply → describe 사이클이 필수
- **오프라인 개발 가능성** — 외부 의존 없이 재현 가능한 환경
- **Phase 3 부하 테스트 단계까지는 "정확한 측정" 요구가 지연됨**

## Decision

Phase 3 에서 K8s 를 처음 도입할 때 **로컬 minikube** 를 사용한다.

- minikube CPU 4 / Memory 8GB
- `k8s/` 하위 매니페스트는 minikube 환경을 기본 가정으로 작성
- 인프라(MySQL/Redis/Kafka)는 클러스터 내부 배포
- kube-prometheus-stack 도 동일 클러스터에 배치
- **적용 범위**: Phase 3 Task 3-1 ~ 3-3 (CI, K8s 배포, 관측성 스택)

> Phase 1·2 의 Docker Compose 환경은 본 ADR 과 무관하며 Phase 3 이후에도 로컬 개발 용도로 계속 사용 가능합니다.

## Alternatives Considered

### Alternative A: Phase 3 부터 바로 클라우드 K8s (GKE/EKS)
- **장점**: Phase 3 후반 부하 테스트와 환경 일관성
- **단점**:
  - Phase 3 전반(CI, 매니페스트, 관측성)은 시행착오가 많아 클라우드 왕복 비용이 생산성 저하 유발
  - 당시 클라우드 크레딧 미확보
- **기각 사유**: Phase 3 전반의 반복 사이클 속도가 후반의 측정 정확도보다 먼저 필요

### Alternative B: kind (Kubernetes in Docker)
- **장점**: minikube 보다 기동 빠름
- **단점**: minikube 의 Addon 생태계가 더 풍부(Ingress, metrics-server 등). macOS 단독 개발 환경에서 차별점 미미
- **기각 사유**: Addon/문서/레퍼런스 양에서 minikube 우세

### Alternative C: Docker Compose 유지 (K8s 도입 유예)
- **장점**: Phase 1·2 연속성
- **단점**: Phase 3 Exit Criteria 에 K8s 배포/HPA 검증이 포함. 도입 유예 불가
- **기각 사유**: Phase 3 의 본질적 주제가 K8s 관측성/HPA

## Consequences

### 긍정적 영향
- Phase 3 Task 3-1 ~ 3-3 이 로컬에서 비용 0 으로 진행됨
- CI → Docker 이미지 → minikube 로드 → apply 의 빠른 반복 사이클 확보
- Phase 3 부하 테스트 전에 K8s/관측성 기본기 학습 완료
- `k8s/overlays/minikube/` 구조는 Phase 3 이후에도 **로컬 개발용 검증 환경**으로 계속 유효 (ADR-0005)

### 부정적 영향 / 트레이드오프
- **부하 테스트의 정확도 한계** — 8GB 메모리 안에 앱/인프라/관측성/부하 도구가 공존하면 부하 도구 자체가 병목이 되어 측정값 왜곡 가능
- **Prometheus/HPA 동작 여유 부족** — HPA max=3 + Prometheus 시계열 누적 시 OOM eviction 위험
- 위 한계는 Phase 3 Task 3-4 부하 테스트 준비 단계에서 명시적으로 드러났으며, **ADR-0004 에서 운영 환경을 GCP/GKE 로 전환하는 직접적 근거**가 되었습니다.

### 후속 결정에 미치는 영향
- ADR-0004 (GCP 전환): 본 ADR 의 한계가 Task 3-4 이후 전환의 직접 근거
- ADR-0005 (Kustomize 구조): minikube 환경을 `overlays/minikube/` 로 보존하여 로컬 개발에서 계속 사용 가능하게 설계

## References
- `docs/04-design-deep-dive.md` 10-7 — 환경별 측정 방침
- `docs/02-architecture.md` 4-3 — 인프라 전략
- `docs/progress/PHASE1.md`, `docs/progress/PHASE2.md` — Docker Compose 기반 Phase 1·2 작업 이력
- `docs/progress/PHASE3.md` — Task 3-1 ~ 3-3 minikube 작업 이력
- 후속: ADR-0004

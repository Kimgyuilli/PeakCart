# Architecture Decision Records

> PeekCart 프로젝트의 주요 아키텍처 결정 이력을 기록합니다.
> 형식: [Michael Nygard ADR](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)

## 원칙

- ADR은 **immutable**. 한 번 작성된 본문은 수정하지 않습니다.
- 결정이 바뀌면 **새 ADR을 작성**하고 기존 ADR의 Status를 `Superseded by ADR-XXXX`로 변경합니다 (Status 줄만 수정 허용).
- Layer 1 설계 문서(01~07)는 **현재 상태(What)** 만 기술하고, **결정 근거(Why)** 는 이 ADR에 기록 후 참조합니다.
- 새 ADR 작성 시 `template.md`를 복사하여 `NNNN-{slug}.md` 형식으로 저장합니다.

## 인덱스

> 신규 ADR 추가 시 이 표의 맨 아래에 행을 추가합니다.
> Status 컬럼 값: `Proposed` · `Accepted` · `Deprecated` · `Superseded`

<!-- INDEX:BEGIN -->
| # | 제목 | Status | Phase | 관련 Layer 1 문서 |
|---|------|--------|-------|-------------------|
| [0001](./0001-layered-ddd-architecture.md) | 4-Layered + DDD 아키텍처 채택 | Accepted | 전체 | 02, 04 |
| [0002](./0002-monolith-to-msa-evolution.md) | 모놀리식 → MSA 단계적 진화 전략 | Accepted | 전체 | 02, 07 |
| [0003](./0003-phase3-initial-minikube.md) | Phase 3 초기 K8s 환경 — 로컬 minikube 채택 | Partially Superseded | Phase 3 Task 3-1~3-3 | 02, 04 |
| [0004](./0004-phase3-gcp-gke-migration.md) | Phase 3 GCP/GKE 환경 전환 | Accepted | Phase 3+ | 01, 04, 07 |
| [0005](./0005-kustomize-base-overlays-structure.md) | Kustomize base/overlays 매니페스트 구조 | Accepted | Phase 3+ | 02 |
<!-- INDEX:END -->

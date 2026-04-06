# ADR-0002: 모놀리식 → MSA 단계적 진화 전략

- **Status**: Accepted
- **Date**: 2026-01-15 (retroactive — Phase 0 설계 시점)
- **Deciders**: 프로젝트 오너
- **관련 Phase**: 전체

## Context

PeekCart는 포트폴리오 목적의 이커머스 플랫폼이며, 다음 두 가지 목표가 공존합니다.

1. **MSA 역량 증명** — 채용 시장에서 요구되는 기술(Kafka, Outbox, Saga, Gateway, CQRS)을 실제로 다뤄본 이력
2. **학습 곡선 관리** — 처음부터 MSA로 시작하면 초기에 네트워크/배포/관측성에 시간이 쏠려 비즈니스 로직 품질이 낮아짐

또한 포트폴리오 스토리텔링 관점에서 "모놀리식의 한계를 직접 경험하고 그 문제를 해결하며 MSA로 진화했다"는 서사가 "처음부터 MSA였다"보다 설득력이 큽니다.

## Decision

**4단계 Phase 진화 전략**을 채택한다.

| Phase | 구조 | 핵심 주제 |
|---|---|---|
| Phase 1 | 모놀리식 | 4-Layered + DDD, `@TransactionalEventListener`, Toss/Slack 연동 |
| Phase 2 | 성능 개선 | Redis 캐싱/분산 락, Kafka + Outbox, DLQ, ShedLock |
| Phase 3 | 인프라/테스트 | CI, K8s, Prometheus/Grafana, 부하 테스트, HPA |
| Phase 4 | MSA 분리 | Gradle 멀티모듈, Spring Cloud Gateway, Choreography Saga, CQRS |

각 Phase는 명시적인 **Exit Criteria**를 가지며, 완료 검증 후 다음 Phase로 진행한다.

## Alternatives Considered

### Alternative A: 처음부터 MSA (Phase 1부터 서비스 분리)
- **장점**: 최종 구조를 일찍 확정. 중간 마이그레이션 비용 없음
- **단점**:
  - 초기 단계에서 서비스 간 통신·배포·관측성 문제가 비즈니스 로직 개발을 블로킹
  - "모놀리식의 어떤 한계가 MSA 전환을 필요하게 만들었는가" 라는 서사가 사라짐
  - 포트폴리오 리뷰 시 "왜 MSA인가?"에 대해 "원래 그렇게 하려고 했습니다" 외에 답이 없음
- **기각 사유**: 학습과 스토리텔링 양쪽에서 손해

### Alternative B: 끝까지 모놀리식
- **장점**: 집중. 단일 배포 유닛의 단순성
- **단점**: 채용 시장에서 요구되는 MSA 관련 기술 스택을 경험하지 못함
- **기각 사유**: 프로젝트의 본래 목적(포트폴리오)과 불일치

### Alternative C: Phase 1 → Phase 4 바로 전환 (Phase 2·3 생략)
- **장점**: MSA 경험을 가장 빨리 확보
- **단점**:
  - Phase 2의 Kafka/Outbox는 MSA에서도 핵심. 모놀리스에서 먼저 숙달하는 편이 MSA 전환 시 리스크 낮음
  - Phase 3의 부하 테스트/HPA는 MSA 전환 전 "기준선"을 제공. 생략 시 "MSA 전환 효과"를 측정할 수 없음
- **기각 사유**: Phase 2·3가 Phase 4의 기반 및 비교 baseline 역할

## Consequences

### 긍정적 영향
- Phase별 완료 검증이 명확 → 중간 상태의 품질 관리 가능
- 각 Phase가 독립된 포트폴리오 에피소드로 기능 (예: "Phase 2에서 동기 이벤트의 한계를 경험하고 Outbox 도입")
- MSA 전환(Phase 4) 시점에 모놀리식 baseline이 존재하여 "개선 효과"를 수치로 비교 가능

### 부정적 영향 / 트레이드오프
- 중간 단계(Phase 1·2의 `@TransactionalEventListener`, 모놀리스 Kafka)는 Phase 4에서 폐기됨 — 일부 코드 재작성 불가피
- Phase 1 코드 일부는 포트폴리오 리뷰 시 "현재는 사용되지 않음"으로 설명 필요

### 후속 결정에 미치는 영향
- ADR-0003/0004 (인프라 환경): Phase별로 적합한 환경이 다를 수 있음을 전제 → Phase 1·2 Docker Compose, Phase 3 초기 minikube, Phase 3 부하 테스트~ GCP 전환의 근거가 됨
- ADR-0005 (Kustomize 구조): Phase 3에서 결정된 매니페스트 구조가 Phase 4 서비스 추가 시 overlay 패턴으로 재사용

## References
- `docs/07-roadmap-portfolio.md` Section 16 — Phase별 작업 및 Exit Criteria
- `docs/02-architecture.md` Section 4-2 — 모놀리식 → MSA 진화
- `docs/04-design-deep-dive.md` Section 10-5 — Phase 1 `@TransactionalEventListener` 한계 → Phase 2 Outbox 전환 근거

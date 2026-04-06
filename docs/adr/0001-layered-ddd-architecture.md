# ADR-0001: 4-Layered + DDD 아키텍처 채택

- **Status**: Accepted
- **Date**: 2026-01-15 (retroactive — Phase 0 설계 시점)
- **Deciders**: 프로젝트 오너
- **관련 Phase**: 전체

## Context

PeekCart는 이커머스 도메인(회원·상품·주문·결제·알림)을 다루며, 도메인 로직이 복잡하고 상태 전이(주문·결제)가 많습니다. 포트폴리오 프로젝트로서 다음을 동시에 만족해야 했습니다.

- **비즈니스 로직의 응집도** — 주문 상태 전이, 재고 차감 등 핵심 규칙이 여러 곳에 흩어지면 유지보수 불가
- **레이어 간 의존성 통제** — 프레젠테이션이 DB에 직접 의존하는 등의 절단면 위반을 방지
- **JPA 편의성과 도메인 순수성의 균형** — 도메인 엔티티와 JPA 엔티티를 분리하면 매핑 보일러플레이트가 급증
- **실무 채용 관점에서 설명 가능한 설계 선택**

## Decision

**4-Layered 구조 + DDD 전술 패턴 + JPA 절충안**을 채택한다.

- 레이어: `presentation/` · `application/` · `domain/` · `infrastructure/`
- 비즈니스 로직 위치: Service가 아닌 **Entity / Domain Service 내부**
- 의존 방향: Presentation → Application → Domain ← Infrastructure
- **JPA 절충안**: 도메인 엔티티에 `@Entity`/`@Id`/`@Column` 등 JPA 어노테이션 허용. 단 `EntityManager`/`Session` 등 JPA API 직접 의존은 금지

## Alternatives Considered

### Alternative A: 전통적 3-Layered (Controller / Service / Repository)
- **장점**: 학습 곡선 낮음, 러닝 코스트 최소
- **단점**: 비즈니스 로직이 Service에 집중되어 "Fat Service / Anemic Domain Model" 안티패턴으로 수렴
- **기각 사유**: 이커머스의 복잡한 상태 전이 로직을 담기 어렵고, 포트폴리오에서 "DDD를 고려했다"는 설명 포인트가 사라짐

### Alternative B: Clean Architecture 완전체 (도메인 엔티티 ↔ JPA 엔티티 분리)
- **장점**: 도메인이 프레임워크에서 완전히 독립. 이론적으로 가장 순수
- **단점**: 도메인 엔티티 ↔ JPA 엔티티 매핑 레이어 필요 → 엔티티/DTO가 2~3배로 증가. 포트폴리오 범위에서 생산성 저하가 학습 이득을 넘어섬
- **기각 사유**: 실무에서도 비순수 절충안이 일반적. 과도한 추상화는 CLAUDE.md §2(Simplicity First) 위반

### Alternative C: Hexagonal (Ports & Adapters)
- **장점**: 외부 시스템(Kafka, Toss, Slack) 연동이 많은 구조에 적합
- **단점**: Port 인터페이스 × Adapter 구현체가 레이어당 중복. 팀 규모 대비 과한 구조
- **기각 사유**: 4-Layered의 Repository 인터페이스(domain) + 구현체(infrastructure) 패턴이 Ports & Adapters의 핵심 이점을 이미 달성

## Consequences

### 긍정적 영향
- 주문 상태 전이 로직이 `Order` 엔티티 내부에 응집 → 테스트 용이, 버그 추적 쉬움
- Repository 인터페이스(domain) ↔ 구현체(infrastructure) 분리로 단위 테스트에서 mock 주입 가능
- 포트폴리오 상에서 "왜 DDD를 부분 적용했는가"의 설명 포인트 확보

### 부정적 영향 / 트레이드오프
- JPA 절충안은 도메인 순수성을 일부 희생 — `@Entity`가 붙은 이상 프레임워크 전환 비용이 존재
- 신규 개발자 온보딩 시 "왜 Service에 로직을 넣지 않는가"를 설명해야 함
- Application 레이어가 너무 얇아지면 오히려 Controller에서 UseCase를 직접 호출하고 싶어지는 유혹 발생

### 후속 결정에 미치는 영향
- ADR-0002 (모놀리식 → MSA): 도메인 경계가 DDD로 정리되어 있어 Phase 4 MSA 분리 시 모듈 추출이 용이
- Phase 4 CQRS 로컬 캐시: 도메인 이벤트 기반 구조가 선행되어 있어 자연스러운 확장

## References
- `docs/02-architecture.md` Section 4-1 — 4-Layered + DDD 구조
- `docs/04-design-deep-dive.md` Section 1 — DDD 전술 패턴 적용 근거
- `CLAUDE.md` — 아키텍처 규칙 (의존 방향, 비즈니스 로직 위치)

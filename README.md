# PeekCart — 고가용성 이커머스 플랫폼 

> **대용량 트래픽 환경을 고려한 이커머스 설계 및 구현**
> Java 17 · Spring Boot 3.x · Kafka · Redis · Toss Payments · Kubernetes
> 작성 기준: Phase 1 (모놀리식) → Phase 4 (MSA) 전체 설계

---

## 📖 프로젝트 문서 (Docs)

1.  [**프로젝트 개요 및 기술 스택**](docs/01-project-overview.md)
    - 프로젝트 개요, 목표, 상세 기술 스택 및 대안 비교
2.  [**아키텍처 설계**](docs/02-architecture.md)
    - 코드 아키텍처 (4-Layered + DDD), 시스템 다이어그램, 패키지 구조
3.  [**요구사항 명세**](docs/03-requirements.md)
    - 기능적/비기능적 요구사항, 핵심 API 명세
4.  [**상세 설계 및 결정 사항**](docs/04-design-deep-dive.md)
    - Kafka 이벤트 설계, 주요 설계 결정 (재고 동시성, Saga 등), 한계 및 트레이드오프
5.  [**데이터베이스 설계**](docs/05-data-design.md)
    - Phase별 ERD 설계 (모놀리식/MSA), 인덱스 전략
6.  [**테스트 전략 및 시나리오**](docs/06-testing-strategy.md)
    - 레이어별 테스트 전략, 성능 테스트 시나리오
7.  [**포트폴리오 어필 및 로드맵**](docs/07-roadmap-portfolio.md)
    - 핵심 스토리라인, 기술별 어필 포인트, 단계별 개발 로드맵

---

## 🛠 프로젝트 요약

| 항목 | 내용 |
| --- | --- |
| 프로젝트명 | PeekCart |
| 유형 | 개인 포트폴리오 프로젝트 |
| 주제 | 대용량 트래픽 환경을 고려한 이커머스 플랫폼 설계 및 구현 |
| 개발 언어 | Java 17 |
| 프레임워크 | Spring Boot 3.x |
| 아키텍처 패턴 | 4-Layered Architecture + DDD |
| 레포 전략 | 모노레포 (Gradle 멀티모듈) |
| 서비스 구조 | 모놀리식으로 시작 후 핵심 서비스 MSA 분리 |
| 인프라 | Kubernetes (minikube 로컬) |

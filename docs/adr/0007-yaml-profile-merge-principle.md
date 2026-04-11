# ADR-0007: YAML 프로파일 병합 원칙 — 연결 정보 vs 동작 정책 분리

- **Status**: Accepted
- **Date**: 2026-04-11
- **Deciders**: 프로젝트 오너
- **관련 Phase**: 전체

## Context

세션 B 부하 테스트(2026-04-09) 중 Grafana API Response Time p95/p99 및 Error Rate 패널이 "No data" 로 표시되는 D-001 이슈가 발견되었다. 근본 원인은 Spring Boot YAML 프로파일 병합 동작이었다.

- `application.yml` (base) 에 `management.metrics.distribution.percentiles-histogram` 설정을 추가하려 했으나, `application-k8s.yml` 에 이미 존재하는 `management` 키 트리(`endpoints`, `endpoint`, `metrics.tags`) 와 병합되는 과정에서 **base 의 `distribution` 하위 키가 프로파일 쪽 `management` 맵으로 가려짐**. 결과적으로 `http.server.requests` histogram bucket 이 비활성화된 상태로 런타임에 전개됨.
- Prometheus 수집은 정상이었으나 `histogram_quantile()` 계산 불가 → Grafana 패널 "No data".
- `fix/d001-metrics-histogram` 브랜치(커밋 `715bcfa`)에서 `MetricsConfig.java` (Java Config + `MeterRegistryCustomizer`) 로 이동하여 봉합.

D-001 은 **Spring Boot YAML 병합 규칙**(최상위 키 기준 맵 병합, 동일 하위 트리 존재 시 프로파일 우선) 의 미묘한 동작 때문에 재발 가능하다. 특히 `management.*`, `spring.kafka.*` 처럼 base 와 프로파일에 동시에 하위 키가 선언된 경우 안전하지 않다.

세션 B 종료 후 전수 리뷰에서 기술 부채 **D-006 (YAML 프로파일 병합 원칙 미명문화)** 로 등록되었고, 세션 C 전 1차 봉합 항목인 리뷰 개선 **P0-B (management 설정 공통화)** 의 이동 범위 결정 기준으로도 본 원칙이 필요하다.

## Decision

**환경별 `application-{profile}.yml` 은 "환경에 따라 달라지는 연결 정보·자격증명"만 선언한다. 런타임 동작을 바꾸는 정책은 `application.yml` (base) 또는 `@Configuration` Java Config 로 관리한다.**

판단 기준(한 줄): **"환경마다 달라야 하는 값인가(→ 프로파일), 아니면 동작 규약인가(→ base/Java Config)?"**

### 허용 / 금지 분류

| 분류 | 프로파일 허용 | 예시 |
|---|---|---|
| 연결 정보 | ✅ | `spring.datasource.url`, `spring.data.redis.host`, `spring.kafka.bootstrap-servers` |
| 자격증명 (환경변수 참조) | ✅ | `spring.datasource.password`, `app.jwt.secret` (env var) |
| 로컬/운영 운영 선택 | ⚠️ 예외 허용 | `logging.level.*`, `spring.jpa.show-sql` (로컬 디버깅) |
| **동작 정책** | ❌ | `management.metrics.distribution.*`, `management.metrics.tags.*`, `spring.kafka.producer/consumer.properties.*`, `spring.jpa.hibernate.ddl-auto` |
| **식별자** | ❌ | `spring.application.name`, `management.metrics.tags.application` |
| **보안 정책** | ❌ | `management.endpoints.web.exposure.include` (최소 노출은 base, 추가 노출만 예외 override) |

### 예외 선언 규칙

회색지대 키(로그 레벨, JPA show-sql 등)를 프로파일에 두는 경우, 해당 YAML 파일 상단에 주석으로 **의도적 예외**임을 명시한다:

```yaml
# application-local.yml
# [ADR-0007 exception] 로컬 디버깅 전용 override — 운영 프로파일 금지
spring:
  jpa:
    show-sql: true
```

### 복잡한 정책은 Java Config 우선

다음 중 하나에 해당하면 YAML 이 아닌 `@Configuration` 클래스로 선언한다:
1. 최상위 키 트리(예: `management.*`)에 base/프로파일 간 병합 충돌 가능성이 있음
2. 조건부 활성화(`@ConditionalOnProperty`) 가 필요함
3. 코드 참조/타입 안전성이 필요함 (`@ConfigurationProperties` 포함)

D-001 의 `MeterRegistryCustomizer` 가 이 케이스의 대표 사례.

## Alternatives Considered

### Alternative A: 전면 Java Config
- **장점**: 병합 이슈 원천 차단, 타입 안전, IDE 탐색 용이
- **단점**: Spring Boot 자동 설정(`application.yml` 기반)의 관용을 버리는 비용 큼. 연결 정보까지 코드로 관리하면 환경 전환이 리빌드/재배포 필요
- **기각 사유**: 과도한 범용화. 연결 정보는 YAML 이 명백히 우월

### Alternative B: 모든 설정 YAML 유지 + 병합 규칙 숙지
- **장점**: 현재 구조 유지, 추가 문서만 필요
- **단점**: D-001 이 증명하듯 병합 규칙은 직관과 다름. "숙지" 에 의존하는 프로세스는 재발 방지 효과 약함
- **기각 사유**: 실패 이력이 있어 문서만으로 불충분

### Alternative C: 하이브리드 (채택)
- **장점**: YAML 의 관용을 유지하되 위험 영역(동작 정책)은 Java Config 로 격리. 판단 기준이 단순("환경 차이 vs 정책")
- **단점**: 회색지대 키에 대해 매번 판단 필요 → 예외 선언 규칙으로 보완
- **채택**: YAML 장점을 유지하면서 D-001 재발 경로를 명시적으로 차단하는 최소 침습 방안

## Consequences

### 긍정적 영향
- **D-001 재발 방지**: `management.*` 트리 같은 충돌 위험 영역이 Java Config 로 격리되므로 병합 이슈가 구조적으로 발생 불가
- **P0-B 실행 기준 확정**: 세션 C 전 리뷰 개선 P0-B 의 "management 공통화" 이동 범위가 본 원칙에 따라 명확해짐
- **CLAUDE.md 경유 자동 전파**: AI 어시스턴트와 개발자 모두 설정 추가 시 동일 기준으로 판단

### 부정적 영향 / 트레이드오프
- **회색지대 판단 비용**: `logging.level.*`, `show-sql` 등은 관용상 프로파일에 두지만 본 원칙의 엄격 해석으로는 "정책" 에 가깝다. 예외 선언 규칙으로 보완하되 완전 자동화는 어려움
- **Java Config 증가**: D-001 의 `MetricsConfig` 처럼 단순한 설정도 Java 코드로 표현 → 보일러플레이트 소폭 증가
- **기존 위반 정리 필요**: 아래 감사 결과의 위반 2건은 P0-B 에서 정리

### 현 시점 YAML 감사 결과 (스냅샷, 2026-04-11)

| 파일 | 키 | 분류 | 조치 |
|---|---|---|---|
| `application-k8s.yml` | `management.metrics.tags.application: peekcart` | 위반(식별자) | **P0-B 에서 base 이동** |
| `application-k8s.yml` | `management.endpoints.web.exposure.include: health,prometheus` | 위반(보안 정책) | **P0-B 에서 base 이동**(최소 노출 기본값) |
| `application-k8s.yml` | `management.endpoint.health.probes.enabled: true` | 회색지대 | 예외 허용 — k8s Probe 운영 기능 |
| `application-k8s.yml` | `management.endpoint.health.show-details: never` | 회색지대 | 후속 검토 (base 기본값 권장) |
| `application-local.yml` | `spring.jpa.show-sql`, `format_sql` | 회색지대 | 예외 허용(로컬 디버깅) — 예외 주석 추가 |
| `application-*.yml` | `logging.level.*` | 회색지대 | 예외 허용(관용) |
| `application-test.yml` | `app.jwt.access-token-expiry` | 회색지대 | 예외 허용(테스트 단축) |

### 후속 결정에 미치는 영향
- **P0-B (management 공통화)**: 본 ADR 감사표의 위반 2건을 이동 대상으로 확정. `health.show-details` 는 별도 결정 없이 현상 유지(회색지대)
- **D-005 (관측성 계약 분산)**: 1차 봉합 범위가 명확해짐. 완전 해결은 Phase 4 전
- **Phase 4 MSA**: 서비스별 설정이 분화되면 본 원칙을 서비스 단위로 동일 적용

## References

- D-001 수정 커밋: `715bcfa` (`MetricsConfig.java`)
- D-006 등록: `docs/TASKS.md` 개발 부채 섹션
- 리뷰 개선 P0-B: `docs/TASKS.md` Task 3-4 + `docs/review/final-report.md`
- CLAUDE.md `§설정 / YAML 프로파일 규칙` (본 ADR 요약 참조용)
- Spring Boot Reference — [Externalized Configuration: Merging YAML](https://docs.spring.io/spring-boot/reference/features/external-config.html)

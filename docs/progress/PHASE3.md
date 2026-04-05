# Phase 3 진행 보고서 — 인프라 / 테스트

> Phase 3 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md` 참고

---

## Phase 3 목표

**Exit Criteria**:
- [x] K8s에 모든 서비스 정상 배포 확인
- [ ] Grafana 대시보드에서 API 응답시간/에러율/Kafka Lag 모니터링 확인
- [ ] nGrinder 부하 테스트 리포트 완성 (캐싱 전/후 TPS 비교 수치 포함)
- [ ] HPA 동작 확인 (Pod 자동 증설 Grafana 스크린샷)

---

## 작업 이력

### 2026-04-03

#### Phase 3 Task 정의

**완료 항목**:
- `docs/TASKS.md`에 Phase 3 Task 5개 정의 (Task 3-1 ~ 3-5)
- `docs/progress/PHASE3.md` 생성

**Task 구성**:
1. Task 3-1: GitHub Actions CI (빌드·테스트·Docker 이미지 파이프라인)
2. Task 3-2: minikube K8s 배포 (매니페스트 작성, 서비스 배포)
3. Task 3-3: kube-prometheus-stack (Prometheus + Grafana 구축)
4. Task 3-4: 부하 테스트 (nGrinder + JMeter, TPS 측정)
5. Task 3-5: HPA 검증 (Order Service 자동 스케일아웃)

#### Task 3-1: GitHub Actions CI 완료

**완료 항목**:
- `Dockerfile` — 멀티스테이지 빌드 (eclipse-temurin:17-jdk → 17-jre), 의존성 레이어 캐싱, non-root 유저 실행
- `.github/workflows/ci.yml` — PR/push 트리거, 단일 job 구조 (빌드+테스트 + 조건부 GHCR push)
- 로컬 Docker 빌드 검증 완료

**주요 결정**:
- **런타임 이미지**: `eclipse-temurin:17-jre` (non-alpine) 선택. Alpine은 ARM64(Apple Silicon) 미지원으로 로컬 빌드 불가. CI(amd64)에서는 동작하지만 로컬 개발 호환성 우선.
- **CI 구조**: 단일 job. test와 Docker push를 분리하면 artifact 전달이 필요해 복잡도 증가. 조건부 step(`if: github.ref == 'refs/heads/main'`)으로 해결.
- **Testcontainers CI 설정**: 추가 설정 없음. ubuntu runner에 Docker 내장, `@ServiceConnection`이 자동 설정하므로 DinD 불필요.
- **이미지 태그**: `latest` + `${{ github.sha }}` — SHA로 추적성, latest로 편의성.

### 2026-04-04

#### Task 3-1: 코드 리뷰 개선 (P0~P1)

**완료 항목**:
- `.dockerignore` 추가 — `.git`, `build/`, `.gradle/`, `docs/`, `.github/` 등 빌드 컨텍스트 제외
- `build.gradle`에 `bootJar { archiveFileName = 'app.jar' }` + `jar { enabled = false }` — JAR 파일명 고정, plain JAR 비활성화
- `Dockerfile` COPY 대상 `*.jar` → `app.jar` 명시적 참조
- `ci.yml`에 `concurrency` 설정 추가 (동일 ref 중복 워크플로우 자동 취소)
- `ci.yml`에 `docker/setup-buildx-action@v3` + `cache-from`/`cache-to: type=gha` — Docker 레이어 캐시 CI 간 공유

**주요 결정**:
- **JAR 파일명 고정**: `archiveFileName = 'app.jar'`로 글로브 패턴 제거. Dockerfile에서 명시적으로 참조하여 복수 JAR 빌드 시 실패 방지.
- **Docker 캐시 전략**: GitHub Actions Cache(`type=gha`) 사용. Registry 캐시 대비 설정 간편하고 추가 인증 불필요.

#### Task 3-2: minikube K8s 배포 완료

**완료 항목**:
- `build.gradle`에 `spring-boot-starter-actuator` 의존성 추가
- `SecurityConfig`에 `/actuator/health/**` 공개 URL 추가 (K8s Probe 인증 없이 접근)
- `application-k8s.yml` — K8s Service DNS 접속 (`mysql:3306`, `redis:6379`, `kafka:29092`), Actuator health probe 노출
- `k8s/namespace.yml` — `peekcart` 네임스페이스
- `k8s/infra/mysql-deployment.yml` — MySQL 8.0 Deployment + Service + PVC(1Gi), readiness/liveness probe
- `k8s/infra/redis-deployment.yml` — Redis 7.2 Deployment + Service, readiness/liveness probe
- `k8s/infra/kafka-deployment.yml` — Apache Kafka 3.8.1 KRaft 단일 노드, Deployment + Service
- `k8s/app/configmap.yml` — `SPRING_PROFILES_ACTIVE=k8s`
- `k8s/app/secret.yml` — DB/JWT/Toss/Slack 키 (`stringData`)
- `k8s/app/peekcart-deployment.yml` — GHCR 이미지, Actuator Liveness/Readiness Probe, NodePort 30080

**주요 결정**:
- **외부 접근 방식**: NodePort(30080) 선택. minikube 환경에서 Ingress Controller 설치보다 단순하고 `minikube service` 명령으로 즉시 접근 가능.
- **Actuator 도입**: Liveness/Readiness Probe를 위해 `spring-boot-starter-actuator` 추가. `/actuator/health/liveness`, `/actuator/health/readiness` 엔드포인트만 노출하여 최소 공개.
- **인프라 리소스 제한**: minikube 8GB 제약 내에서 MySQL(512Mi~1Gi), Redis(128Mi~256Mi), Kafka(512Mi~1Gi), App(512Mi~1Gi) 할당.
- **Kafka 리스너 구조**: docker-compose의 EXTERNAL 리스너 제거. K8s 내부 통신은 `PLAINTEXT://kafka:29092`로 통일 (Service DNS).
- **imagePullPolicy: Never**: GHCR private 레포 인증 문제 회피. `eval $(minikube docker-env)` + 로컬 빌드로 minikube에 직접 이미지 적재.

#### Task 3-2: 코드 리뷰 개선 (P1~P2)

**완료 항목**:
- `k8s/infra/mysql-deployment.yml` — MySQL 크레덴셜 하드코딩 제거, `secretKeyRef`로 `peekcart-secret` 참조
- `k8s/infra/redis-deployment.yml` — PVC 512Mi 추가 + `volumeMount` (JWT 블랙리스트 영속화)
- `k8s/infra/kafka-deployment.yml` — PVC 1Gi 추가 + `volumeMount` (미소비 메시지 유실 방지)
- `k8s/app/peekcart-deployment.yml` — `startupProbe` 추가 (`failureThreshold: 30`, `periodSeconds: 5`, 최대 150초 기동 대기), readiness/liveness에서 `initialDelaySeconds` 제거 (startupProbe가 대체)
- 전체 매니페스트에 K8s 권장 labels 추가 (`app.kubernetes.io/name`, `app.kubernetes.io/component`, `app.kubernetes.io/part-of`)

**주요 결정**:
- **MySQL 크레덴셜 Secret 통합**: MySQL env에 하드코딩된 비밀번호를 `peekcart-secret`의 `DB_USERNAME`/`DB_PASSWORD`로 통일. 크레덴셜 관리 포인트 단일화.
- **Redis/Kafka PVC 추가**: Pod 재시작 시 JWT 블랙리스트 유실(보안 이슈) 및 Kafka 미소비 메시지 유실 방지. MySQL과 동일하게 PVC 영속화.
- **startupProbe 도입**: Spring Boot 기동 시간이 `livenessProbe.initialDelaySeconds`를 초과하면 Pod가 kill되는 문제 방지. startupProbe가 기동 완료를 보장한 후 liveness/readiness가 동작.
- **K8s 권장 labels**: Task 3-3 ServiceMonitor selector 설정 연계를 위해 `app.kubernetes.io/*` labels 사전 추가.

### 2026-04-05

#### Task 3-3: kube-prometheus-stack 완료

**완료 항목**:
- `build.gradle` — `micrometer-registry-prometheus` + `logstash-logback-encoder:8.0` 의존성 추가
- `application-k8s.yml` — Actuator 엔드포인트 `health,prometheus` 노출
- `SecurityConfig` — `/actuator/prometheus` 공개 URL 추가 + `MdcFilter` 등록 (JwtFilter 뒤)
- `logback-spring.xml` — `springProfile` 기반 분기: k8s=JSON (`LogstashEncoder`), local=plain text (Spring Boot 기본)
- `MdcFilter` — 요청마다 `traceId`(UUID 16자리) + `userId`(SecurityContext) MDC 설정
- `k8s/monitoring/values-prometheus.yml` — minikube 경량 Helm values (총 ~1.2GB, Alertmanager 비활성화)
- `k8s/monitoring/install.sh` — Helm 설치 스크립트
- `k8s/monitoring/servicemonitor.yml` — PeekCart 메트릭 스크래핑 (15초 간격, `/actuator/prometheus`)
- `k8s/app/peekcart-deployment.yml` — Service 포트 `name: http` 추가 (ServiceMonitor 연계)
- `k8s/monitoring/dashboards/` — Grafana 대시보드 3개 (API&JVM, Kafka Lag, Pod Resources&HPA) + ConfigMap 프로비저닝
- `k8s/monitoring/alerts/grafana-alerts.yml` — Grafana unified alerting 규칙 2개 (5xx 에러율 5% 초과, p95 응답시간 2s 초과)

**주요 결정**:
- **트레이싱 방식**: MDC UUID 수동 생성. Brave/Zipkin 미도입 — 모놀리스 Phase에서 cross-service tracing 불필요. Phase 4 MSA 전환 시 Micrometer Tracing 도입 예정.
- **Actuator 노출 범위**: `health,prometheus`만 노출. `metrics`, `env` 등은 보안상 비노출. SecurityConfig에 `/actuator/prometheus` 단일 추가 (와일드카드 `/actuator/**` 회피).
- **로깅 분기 전략**: `logback-spring.xml`의 `springProfile`로 k8s/local 분기. 로컬 개발 영향 없음 (plain text 유지). `LogstashEncoder`가 MDC 필드(`traceId`, `userId`, `orderId`)를 JSON에 자동 포함.
- **모니터링 네임스페이스 분리**: `monitoring` 네임스페이스 별도 생성. kube-prometheus-stack CRD/RBAC를 `peekcart`와 분리하여 관심사 격리.
- **Alertmanager 비활성화**: Grafana unified alerting으로 대체. 스택 단순화 + minikube 리소스 절약.
- **Kafka 모니터링**: JMX exporter 없이 Micrometer consumer lag 메트릭만 수집. `kafka_consumer_fetch_manager_records_lag_max`로 consumer 관점 lag 모니터링 충분.
- **리소스 할당**: Prometheus 512Mi, Grafana 256Mi, node-exporter 128Mi, kube-state-metrics 128Mi, operator 128Mi. 모니터링 총 ~1.2GB — 기존 인프라 ~2.5GB + App 1Gi와 합쳐 minikube 8GB 내 수용.

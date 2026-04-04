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

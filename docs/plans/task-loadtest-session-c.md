# task-loadtest-session-c — Phase 3 세션 C 실행 + Task 3-4 / 3-5 마무리

> 작성: 2026-04-24
> 관련 Phase: Phase 3 — Task 3-4 세션 C 본편 + Task 3-5 HPA 런타임 검증
> 관련 ADR: ADR-0004 (GKE 운영 체크리스트 · immutable), ADR-0006 (monitoring 환경 분리)
> 관련 선행 계획서: `docs/plans/done/task-jmeter-to-k6.md` (P10·P11 을 본 task 로 이관), `docs/plans/done/task-hpa-manifest.md` (HPA 매니페스트 작성 완료)

## 1. 목표

Phase 3 의 남은 런타임 검증을 **GKE 과금 1회** 로 묶어 완료한다. 대상 작업은 세 축이다.

- **Task 3-4 세션 C** — k6 로 시나리오 2(1,000 VUser 동시 주문 정합성)와 시나리오 3(Kafka Consumer Lag) 를 실측하고, 세션 B 수치(시나리오 1 캐시 전/후) 와 결합해 Task 3-4 리포트를 최종화한다.
- **Task 3-5 HPA 런타임 검증** — 시나리오 2 부하 중 `peekcart` Deployment 가 min 1 → max 3 으로 자동 증설되는 것을 Grafana `PeekCart — Pod Resources & HPA` 대시보드로 관찰·캡처한다. HPA 매니페스트 작성은 `task-hpa-manifest` 에서 완료되어 `k8s/overlays/gke/hpa.yml` 이 이미 존재한다.
- **D-002 판단 데이터 수집** — 세션 B 단일 Pod 캐시 TPS ×2.31 (목표 3× 미달) 의 원인이 CPU 병목인지, HPA 스케일아웃 구간에서 동시 주문 처리량·p95 가 개선되는지 수치로 확인한다. **판단 결론 자체는 본 task 비대상** (TASKS.md D-002 행 갱신은 수집된 수치를 근거로 다음 세션에서 수행).

세션 B 시나리오 1(nGrinder, 2026-04-09) 은 **재측정하지 않는다** — 세션 B 리포트를 인용한다.

## 2. 배경 / 제약

- ADR-0004 운영 체크리스트(GKE 클러스터/loadgen VM 프로비저닝·이미지 빌드·폐기) 는 사용자 수동 수행이 원칙이다. 본 계획서는 kubectl/k6/gcloud 명령 순서를 제시하되 실제 `gcloud container clusters create` · `docker push` · `gcloud compute instances create` · `cleanup.sh` 는 사용자가 집행한다.
- **측정 후 즉시 폐기**: 클러스터·loadgen VM 은 세션 종료 시 `loadtest/cleanup.sh` + 콘솔 육안 확인으로 회수한다 (ADR-0004).
- Prometheus remote-write receiver(`enableRemoteWriteReceiver: true`) 는 `task-jmeter-to-k6` 에서 `k8s/monitoring/gke/values-prometheus.yml` 에 이미 반영되어 있다. 본 task 는 **활성 상태 smoke test 만** 수행한다.
- k6 스크립트(`loadtest/scripts/order-concurrency.js`) 와 users.csv 는 이미 존재한다. **로컬 리허설(docker-compose + k6 run --vus 10 --duration 30s)** 은 `task-jmeter-to-k6` 계획서에서 명시적으로 deferred 되었으므로 본 task P1 에서 먼저 수행한다 (과금 전 스크립트 정상 동작 확인).
- k6 Grafana 대시보드(id 19665) JSON 의 `k8s/monitoring/shared/` SSOT 커밋은 세션 C 당일 실제 import·렌더링 검증 후 **별도 PR** (선행 계획서 §7 원칙 유지 — 본 task 비대상).
- 앱 소스(`src/`) · Flyway · k8s overlays · Helm values 는 **불변**. 본 task 는 관측·기록 범위로 한정된다.

## 3. 작업 항목

### Part A — 선행 리허설 (무과금)

- [ ] **P1.** k6 스크립트 로컬 리허설 (`task-jmeter-to-k6` P7 deferred 분 회수)
  - `docker-compose up -d` + 앱 로컬 실행
  - `bash loadtest/scripts/generate-users-csv.sh` (users.csv 재생성 확인)
  - `mysql < loadtest/sql/seed.sql` 후 카운트: users=1101, products=1010, 경합 product_id 1001~1010 각 stock=100
  - `mkdir -p loadtest/reports/local && k6 run --vus 10 --duration 30s --summary-export=loadtest/reports/local/k6-summary.json -e BASE_URL=http://localhost:8080 loadtest/scripts/order-concurrency.js`
  - 검증
    - 로그인 check 성공률 > 99%
    - **cart/items 단계 성공 확인** — k6 summary 에 `name="cart"` 태그가 존재하고 실패율 < 1% (3-step 플로우 중간 단계 누락 방지, `task-jmeter-to-k6.md:57-60` 플로우 정의)
    - 주문 응답 코드 201 / 409 / 400 만 존재 (5xx 0건)
    - `mysql < loadtest/sql/verify-concurrency.sql` consistency=OK
    - `jq .metrics < loadtest/reports/local/k6-summary.json` non-empty (Threshold exit code 0)
  - 실패 시 세션 C 진입 금지 — 스크립트/시드/앱 이슈를 해당 단계에서 해결

### Part B — GKE 환경 기동 (과금 시작)

- [ ] **P2.** 클러스터/VM 프로비저닝 (사용자 수동, ADR-0004 운영 체크리스트)
  - GKE Standard: asia-northeast3-a, `peekcart-loadtest`, e2-standard-4 × 1
  - Artifact Registry 푸시: `asia-northeast3-docker.pkg.dev/<project>/peekcart/peekcart:<sha>`
  - loadgen VM: e2-standard-2, 동일 zone, OS Ubuntu 22.04
- [ ] **P3.** 앱/모니터링 배포 (`k8s/overlays/gke/README.md:48-68` 4단계 순서 준수 — ServiceMonitor CRD 선행 의존, ADR-0006 불변식 1·4·5)
  1. `kubectl apply -f k8s/monitoring/namespace.yml` — monitoring namespace 생성
  2. `bash k8s/monitoring/gke/install.sh` — kube-prometheus-stack 설치 + CRD 등록
  3. `kubectl apply -k k8s/monitoring/shared/` — 대시보드·alert SSOT
  4. `kubectl apply -k k8s/overlays/gke/` — app + HPA + ServiceMonitor
  - smoke test
    - `kubectl -n peekcart get pods` → peekcart 1/1 Running
    - `kubectl -n peekcart get hpa peekcart` → `REFERENCES: Deployment/peekcart`, `MINPODS=1`, `MAXPODS=3`, `TARGETS: <x>%/60%`
    - `kubectl -n peekcart get svc peekcart` → Internal LB `EXTERNAL-IP` 확보 (세션 C BASE_URL)
    - `/actuator/health` 200, `/actuator/prometheus` 에 histogram bucket + `application="peekcart"` tag 존재
    - **metrics-server 가용성** (HPA 전제, `k8s/overlays/gke/README.md:71-72`): `kubectl top pods -n peekcart` 가 CPU/메모리 수치 반환. `metrics not available yet` 면 1분 대기 후 재시도, 지속 실패 시 세션 중단 (HPA 가 `<unknown>/60%` 상태로 머무르면 증설 자체 불가)
  - **Prometheus receiver 활성 검증** (`task-jmeter-to-k6` P8-b 계약, 운영자 로컬 머신에서 1회 smoke)
    - `kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090 &`
    - `curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:9090/api/v1/write`
    - **기대값 400** (빈 바디가 malformed write request 로 거절 = receiver enabled). `404` 면 values 반영 누락 → 세션 중단, values 재확인
    - smoke 완료 후 운영자 로컬 port-forward 는 종료 (loadgen VM 에서 별도 기동 — P4)
- [ ] **P4.** 시드 + loadgen 준비
  - GKE MySQL Pod 로 `kubectl exec -i -- mysql < loadtest/sql/seed.sql`, 카운트 검증 (P1 동일 기준)
  - loadgen VM 셋업 (SSH 접속 상태)
    - k6 v0.49+ 설치 (`task-jmeter-to-k6` P8-a README 절차), `users.csv` 복사
    - **GKE 접근용 kubeconfig 배포** — loadgen VM 자체에서 Prometheus receiver 로 remote-write 하기 위함. `k8s/monitoring/gke/values-prometheus.yml` 에는 Prometheus `service.type` 이 명시되지 않아 kube-prometheus-stack chart 기본값(ClusterIP)을 따르며 Internal LB 미노출로 추정. Helm values 불변 유지하면서 접근성을 확보하는 경로
      - `gcloud auth login` (또는 VM 부착 서비스계정으로 `gcloud container` 권한 부여)
      - `gcloud container clusters get-credentials peekcart-loadtest --zone=asia-northeast3-a`
    - **VM 내부에서 Prometheus port-forward 기동**
      - `nohup kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090 >/tmp/pf.log 2>&1 &`
      - 접근성 확인: `curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:9090/api/v1/write` → **400** (VM loopback 에서 receiver 도달)
  - `export BASE_URL=http://<internal-lb>:8080` 확보 (`kubectl -n peekcart get svc peekcart`)
  - `export PROM_RW=http://localhost:9090/api/v1/write` (loadgen VM loopback, P0 #2 경로 (a))

### Part C — 세션 C 실측

- [ ] **P5.** Grafana 사전 세팅
  - port-forward 로 Grafana 접근: `kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80`
  - Dashboard → Import → `19665` → datasource: Prometheus (`uid: prometheus`)
  - 관찰 대상 대시보드 3종을 별도 탭에 열어둠
    - `19665` (k6 Load Testing Results)
    - `PeekCart — Pod Resources & HPA` (HPA scale event 관찰)
    - `PeekCart — Kafka Consumer` 또는 kube-prometheus-stack 기본 `Kafka / Consumer Lag`
  - **Kafka Lag PromQL placeholder 치환** (P6 진입 전 필수)
    - Kafka Consumer Lag 대시보드의 패널 쿼리를 열어 실제 metric명 (`kafka_consumergroup_lag` 또는 `kafka_consumer_group_lag` 등) · consumer group label 키 (`group` / `consumergroup` 등) · 실제 group 식별자 (`order-created` / `payment.approved` 가 실존하는지) 를 확인
    - §5 Kafka Lag 합격선의 예시 PromQL 을 실측용 쿼리로 치환하고, 치환한 최종 쿼리 문자열을 P7 리포트 시나리오 3 섹션에 기록 대상으로 메모
- [ ] **P6.** 시나리오 2 실행 (1,000 VUser 동시 주문 + HPA + Kafka Lag 동시 관찰)
  - 측정 시작 직전 상태 스냅샷
    - `kubectl -n peekcart get pods -l app.kubernetes.io/name=peekcart -o wide` → replicas=1 확인
    - `kubectl -n peekcart get hpa peekcart -w &` (증설 이벤트 타임라인 로그 수집)
  - k6 실행 (loadgen VM)
    - ```
      mkdir -p /tmp/loadtest/$(date +%F)
      k6 run \
        --summary-export=/tmp/loadtest/$(date +%F)/k6-summary.json \
        -e BASE_URL=${BASE_URL} \
        -o experimental-prometheus-rw=${PROM_RW} \
        loadtest/scripts/order-concurrency.js
      ```
  - 실행 중 관찰 (시나리오 2 = 시나리오 3 동시 관찰)
    - k6 대시보드에서 VU 추이, http_req_duration p95, http_req_failed rate 실시간 캡처
    - HPA 대시보드에서 replicas 1 → 2 → 3 증설 시점 캡처 (Task 3-5 핵심 산출물)
    - Kafka Consumer Lag 대시보드에서 order-created / payment.approved consumer group lag 추이 캡처 (시나리오 3 핵심 산출물)
  - 실행 직후 정합성 검증
    - `mysql < loadtest/sql/verify-concurrency.sql` 결과 저장 (`loadtest/reports/YYYY-MM-DD/verify-concurrency.txt`)
    - 기대: 재고 합계 0, 오버셀링 0건, 주문 성공 수 == 재고 소진 수, 상태 불일치(inventory ≠ order) 0건
- [ ] **P7.** 리포트 작성
  - `cp loadtest/reports/TEMPLATE.md loadtest/reports/YYYY-MM-DD/REPORT.md` 후 채움
  - 기록 항목 (TEMPLATE §(a)~(f))
    - 환경 스펙 / 도구 버전 / 시나리오 파라미터
    - 시나리오 1 결과는 **세션 B 인용** (`../2026-04-09/REPORT.md` 상대 경로 링크), 재측정 없음 명시
    - 시나리오 2 결과: TPS, p95, error rate, 정합성 판정 (consistency=OK)
    - 시나리오 3 결과: Kafka Consumer Lag peak / 정상 구간 복귀 시간
    - **Task 3-5 HPA 결과**: 증설 타임라인(replicas 1→N 시점, 부하 시작 기준 경과 시간), 최종 replica 수, CPU utilization 추이
    - **D-002 데이터 포인트**: 세션 B 단일 Pod 캐시 전/후 TPS 비교(×2.31, 인용) + 세션 C 시나리오 2 구간에서 HPA 증설 후 처리량/p95 변화 관찰 수치. 원인 가설 3종(Redis 직렬화 · 커넥션 풀 · JSON 응답 직렬화)에 대한 수치적 정/반 증거를 가능한 범위에서 기록. **최종 판단은 본 task 비대상**
  - 첨부
    - `k6-summary.json`
    - `grafana/` 디렉토리에 스크린샷 PNG (k6 dashboard 1장, HPA dashboard 2장(증설 전/후), Kafka Lag dashboard 1장, 최소 4장)
    - `verify-concurrency.txt`

### Part D — 정리 / 기록

- [ ] **P8.** 클러스터 폐기 (사용자 수동)
  - `bash loadtest/cleanup.sh --dry-run` 으로 대상 확인 → `bash loadtest/cleanup.sh` 실행
  - 콘솔 육안 확인: PD(`gcloud compute disks list`), 예약 IP(`gcloud compute addresses list`), loadgen VM, Artifact Registry 이미지 잔존 여부
  - billing alert 상태 확인 (스크립트 미관여)
- [ ] **P9.** 문서 상태 갱신
  - `docs/TASKS.md`
    - Task 3-4 표: k6 설치, 시나리오 2, 시나리오 3, 테스트 리포트 → ✅ 체크 + 비고에 리포트 경로
    - Task 3-4 **상태**: `🔄 진행 중` → `✅ 완료`, 완료 기준 충족 명시
    - Task 3-5 표: metrics-server 확인, Pod 자동 증설, Grafana 스크린샷 → ✅ 체크 + 리포트 Part D 참조
    - Task 3-5 **상태**: `🔲 대기` → `✅ 완료`
    - D-002 행: 수집된 세션 C 데이터 포인트 링크만 추가 (판단 결론 변경 없음)
    - §완료된 작업 표: 2026-MM-DD 행 추가 (세션 C 실행 · Task 3-4/3-5 완료 요약)
  - `docs/progress/PHASE3.md`: 세션 C 엔트리 신규 (Part A~D 요약, Task 3-4/3-5 완료, D-002 데이터 포인트 참조, Phase 3 Exit Criteria 진척)
- [ ] **P10.** k6 대시보드 JSON SSOT 커밋 **(별도 PR, 본 task 비대상)**
  - 세션 C 에서 실제 import 한 19665 대시보드 JSON export → `k8s/monitoring/shared/dashboards/k6-load-testing.json`
  - `k8s/monitoring/shared/kustomization.yml` configMapGenerator 에 등록
  - 별도 브랜치 `feat/monitoring-k6-dashboard-ssot` 로 PR

## 4. 영향 파일

| 파일 | 종류 | 변경 요지 |
|------|------|----------|
| `loadtest/reports/YYYY-MM-DD/REPORT.md` | 신규 | 세션 C 리포트 (시나리오 2·3 + HPA + D-002 데이터 포인트) |
| `loadtest/reports/YYYY-MM-DD/k6-summary.json` | 신규 | k6 summary export |
| `loadtest/reports/YYYY-MM-DD/grafana/*.png` | 신규 | 스크린샷 최소 4장 (k6·HPA 2장·Kafka Lag) |
| `loadtest/reports/YYYY-MM-DD/verify-concurrency.txt` | 신규 | 정합성 검증 쿼리 결과 |
| `loadtest/reports/local/k6-summary.json` | 신규 (git ignore 대상) | P1 로컬 리허설 산출물, 커밋 비대상 |
| `docs/TASKS.md` | 수정 | Task 3-4/3-5 완료 처리, D-002 데이터 포인트 링크, 완료 작업 표 행 추가 |
| `docs/progress/PHASE3.md` | 수정 | 세션 C 이력 엔트리 신규 |
| `docs/adr/*` | **불변** | 세션 C 는 실측 범위, ADR 비대상 |
| `src/**`, `k8s/**`, `loadtest/scripts/**`, `loadtest/sql/**`, `loadtest/README.md` | **불변** | 본 task 는 실행/관측/기록 범위 |
| `k8s/monitoring/shared/dashboards/k6-load-testing.json` | **별도 PR (P10)** | 본 task 비대상 |

## 5. 검증 방법

- **P1 로컬 리허설 게이트** — P1 검증 **5항목**(로그인 성공률 > 99% / cart 단계 `name="cart"` 실패율 < 1% / 주문 응답 5xx 0건 / consistency=OK / summary.json 파싱) 모두 통과해야 P2 진입
- **P3 receiver 활성** — `curl -X POST .../api/v1/write` 기대값 **400** (enabled), `404` 면 중단
- **P3 관측성 계약 smoke** — `/actuator/prometheus` 응답에 `http_server_requests_seconds_bucket{application="peekcart",uri="/api/v1/products"}` 존재 (D-001 재발 방지 회귀)
- **P6 측정 무결성**
  - k6 Threshold `http_req_failed{scenario:contention}: rate<0.1` 통과
  - `k6-summary.json` 의 `name="login"`, `name="cart"`, `name="order"` 3종 태그 모두 존재하고 `name="cart"` 실패율 < 1% (3-step 플로우 유지 검증 — 로컬 리허설 P1 과 동일 계약)
  - 주문 응답 5xx 0건 (로그: `kubectl -n peekcart logs -l app.kubernetes.io/name=peekcart --since=10m | grep -c ' 5[0-9][0-9] '` → 0)
  - `verify-concurrency.sql` consistency=OK, 오버셀링 0
- **Task 3-5 증설 실증** (`docs/TASKS.md:420` 완료 기준 = `Pod 1개 → 3개 자동 증설`)
  - `kubectl -n peekcart get hpa peekcart -w` 로그에 replicas **1 → 3** 전이 이벤트 존재 (중간 2 경유 허용)
  - Grafana HPA 대시보드 스크린샷에 replica=3 도달 순간 포함
  - **미달 시(예: 2 에서 saturation) Task 3-5 "미완료" 유지** — 원인 후보(부하 부족·HPA behavior 지연·metrics lag) 를 리포트 Part C 에 기록. `≥2` 로 완료 승격은 TASKS.md/ADR 선행 갱신이 있어야 가능하며 본 task 범위가 아님
- **시나리오 3 Kafka Lag 합격선** (`docs/TASKS.md:397` 목표 = `정상 구간 Lag 0 유지`)
  - steady-state (부하 시작 전 · k6 종료 후 2분) consumer group lag == 0 for `order-created`, `payment.approved`
  - peak 구간 lag 허용. 단 k6 종료 후 **5분 이내** 0 복귀. 미복귀 시 시나리오 3 "미완료" 판정 + consumer 로그·PromQL 재확인 후 원인 리포트에 기록 (Task 3-4 미완료 유지)
  - PromQL 예 (metric명/label은 placeholder — 세션 C 당일 실제 exporter 대시보드로 확정 후 치환): `max_over_time(kafka_consumergroup_lag{group=~"order-created|payment.approved"}[2m]) == 0` 가 steady-state 2분 구간에서 성립
- **P7 리포트 완결성**
  - TEMPLATE §(a)~(f) 7개 섹션 모두 채워짐
  - 시나리오 1 은 세션 B 인용 링크 (재측정 수치 기재 금지)
  - D-002 데이터 포인트가 수치 형태로 존재 (판단 결론 문장 금지)
- **P8 정리 완결**
  - `gcloud compute disks list` / `addresses list` / `compute instances list --filter name~'loadgen|peekcart'` 출력이 빈 배열
  - Artifact Registry 이미지는 재사용 가능성 고려해 보존 가능 — 단 태그·크기를 PHASE3.md 에 기록
- **문서 회귀**
  - `docs/TASKS.md` L487 인근 완료 이력 행은 **수정 금지** (과거 사실)
  - Task 3-4/3-5 체크박스가 `✅ 완료` 로 전환되어도 "완료된 작업" 표에 중복 기입하지 않음 (새 행만 추가)

## 6. 완료 조건

- P1 로컬 리허설 **5항목** 검증 통과 (login / cart / 주문 5xx / consistency / summary 파싱)
- P3 receiver smoke test 400 확인
- **P3 관측성 계약 smoke 통과** — `/actuator/prometheus` 응답에 `http_server_requests_seconds_bucket{application="peekcart",uri="/api/v1/products"}` 존재 (D-001 재발 방지, ADR-0006 관측성 drift 방지 전제)
- P6 시나리오 2 k6 Threshold 통과 + `k6-summary.json` 에 login/cart/order 3종 태그 + cart 실패율 < 1% + consistency=OK + 오버셀링 0
- P6 HPA replica **1 → 3** 전이가 `kubectl get hpa -w` 로그와 Grafana 스크린샷 양쪽으로 확인됨 (TASKS.md:420 완료 기준)
- P6 Kafka Consumer Lag 합격선 충족 (steady-state 0 + peak 후 5분 내 0 복귀) + 추이 스크린샷 확보
- **P5 에서 placeholder 치환한 실측용 Kafka Lag PromQL 쿼리와 참고한 대시보드 id 가 P7 리포트 시나리오 3 섹션에 기록**
- P7 리포트가 TEMPLATE §(a)~(f) 모두 채워진 상태로 `loadtest/reports/YYYY-MM-DD/` 에 존재 + 시나리오 1 은 세션 B 리포트 인용 링크로 대체(재측정 수치 기재 금지) + D-002 데이터 포인트가 수치 형태로 존재 (§5 P7 리포트 완결성과 동일 기준)
- P8 클러스터/VM/PD/IP 회수 완료
- P9 TASKS.md 에서 Task 3-4 · Task 3-5 가 `✅ 완료` 상태, PHASE3.md 에 세션 C 엔트리 존재

## 7. 트레이드오프 / 비대상

- **세션 B 시나리오 1 재측정 없음** — 이종 도구 하이브리드(nGrinder/k6) 유지 (선행 계획서 §7 원칙 계승)
- **D-002 최종 판단은 본 task 비대상** — 본 task 는 데이터 포인트(세션 B 인용 + 세션 C HPA 구간 수치) 수집까지. 원인 3종 가설 중 어느 것이 병목인지 결론 짓는 건 프로파일링/별도 분석 필요
- **k6 대시보드 JSON SSOT 커밋은 별도 PR** (P10) — 실제 import·렌더링 검증 후 커밋 원칙
- **HPA behavior 튜닝 없음** — `scaleUp=30s / scaleDown=300s` 초기값 유지. 결과에 따른 재조정은 후속 task
- **모놀리스 한정 측정** — Task 3-5 는 Phase 4 MSA 분리 이후 `Order Service` 재정의 예정. 본 task 는 현재 `peekcart` Deployment 기준
- **nGrinder 재사용 없음** — 시나리오 1 과거 자료만 활용. 세션 C 에서 nGrinder agent 기동하지 않음 (JDK 11 요구 무관)
- **과금 세션 1회 통합** — Task 3-4 / 3-5 / D-002 데이터 수집을 분리 세션으로 돌리는 대신 1회에 묶음. 장애·재측정 사유 발생 시 비용 증가 리스크 감수

## 8. 커밋 / 브랜치 전략

**브랜치**: `test/phase3-loadtest-session-c`

**커밋 구조 (4분할, 기록 커밋 위주)**

1. `docs(loadtest): add session C report for task 3-4 + 3-5` — P7 산출물 (`loadtest/reports/YYYY-MM-DD/` 디렉토리 일체)
2. `docs(tasks): mark task 3-4 + 3-5 complete after session C` — P9 TASKS.md 갱신 (Task 3-4/3-5 ✅, D-002 데이터 포인트 링크, 완료 작업 표 행)
3. `docs(progress): record phase3 session C in PHASE3` — P9 PHASE3.md 이력 엔트리
4. (별도 브랜치/PR) `feat(monitoring): commit k6 dashboard JSON to shared SSOT` — P10

**Revert 시나리오**
- commit 1 단독 revert → 리포트 제거, TASKS/PHASE3 는 "완료" 상태 유지 → 명시적 불일치 (의도적 revert 는 3 → 2 → 1 순서)
- commit 2 + 3 동시 revert → 완료 상태 롤백, 리포트는 보존 (증빙 유지)
- commit 3 단독 revert → PHASE3 이력만 제거 — 무해

## 9. 리뷰 이력

### 2026-04-24 11:16 — GP-2 (loop 1)
- 리뷰 항목: 4건 (P0:2, P1:2, P2:0)
- 사용자 선택: [1] P0/P1 모두 반영
- 수용 항목
  - **P0 #1 (bug)** — P3 배포 순서를 `k8s/overlays/gke/README.md:48-68` 의 4단계 (namespace → install.sh → monitoring/shared → overlays/gke) 로 재작성. ServiceMonitor CRD 선행 의존 명시
  - **P0 #2 (architecture)** — P0 #2 접근 경로 (a) 채택. P4 에 loadgen VM 내부 kubeconfig 배포 + VM 자체에서 port-forward 기동 단계 추가. `PROM_RW=http://localhost:9090/api/v1/write` 로 변경. Helm values (`values-prometheus.yml`) 불변 유지
  - **P1 #3 (doc)** — §5 Task 3-5 증설 실증 / §6 완료 조건의 `1 → ≥2` 를 `1 → 3` 으로 복구. `docs/TASKS.md:420` SSOT 기준 일치. 미달 시 "미완료 유지" 정책 명시
  - **P1 #4 (test)** — (a) P3 smoke 에 `kubectl top pods` metrics-server 가용성 확인 추가. (b) §5 에 Kafka Lag 합격선 (steady-state 0 + peak 후 5분 내 0 복귀) + PromQL 예시 + 미복귀 시 fallback 추가
- P0 무시 사유: 해당 없음 (전건 수용)
- raw: `.cache/codex-reviews/plan-task-loadtest-session-c-1776996850.json`
- run_id: `plan:20260424T021329Z:d0ddb115-51f7-4a61-aa53-fcba52399bf8:1`

### 2026-04-24 19:45 — GP-2 (loop 2)
- 리뷰 항목: 4건 (P0:0, P1:2, P2:2)
- 사용자 선택: [2] 전체 반영 ("동의하는 부분 반영" — 소스 검증 결과 4건 모두 유효)
- 수용 항목
  - **P1 #1 (doc)** — P4 line 의 `values-prometheus.yml:38-57` 오인용 제거. Prometheus Service 미명시 → chart 기본값(ClusterIP) 추정 문구로 완화 (추측 금지 원칙 준수)
  - **P1 #2 (bug)** — §5 Kafka Lag PromQL 예시를 `max_over_time(kafka_consumergroup_lag{...}[2m]) == 0` 으로 문법 수정 + "metric명/label은 placeholder, 세션 C 당일 exporter 대시보드로 확정 후 치환" 명시
  - **P2 #3 (test)** — P1 로컬 리허설 검증에 cart/items 단계 성공 체크 (`k6 summary 의 name="cart"` 실패율 < 1%) 추가. 3-step 플로우 (login → cart → order) 중간 단계 누락 방지
  - **P2 #4 (doc)** — §6 P7 완료 조건에 §5 의 두 요구사항(세션 B 인용 링크 / D-002 수치형 데이터) 명시적 병합. §5↔§6 완료 판정 기준 일원화
- P0 무시 사유: 해당 없음 (P0 0건)
- raw: `.cache/codex-reviews/plan-task-loadtest-session-c-1777027438.json`
- run_id: `plan:20260424T104334Z:d0ddb115-51f7-4a61-aa53-fcba52399bf8:2`

### 2026-04-24 21:03 — GP-2 (loop 3)
- 리뷰 항목: 3건 (P0:0, P1:1, P2:2)
- 사용자 선택: [2] 전체 반영 ("동의하는 부분 반영" — 3건 모두 유효 판정)
- 수용 항목
  - **P1 #1 (test)** — §5 P1 게이트와 §6 완료조건의 "4항목" → "5항목" 수정 + cart 검증 명시. §5 P6 측정 무결성에 `k6-summary.json` 3종 태그(login/cart/order) 존재 + cart 실패율 < 1% 추가. 로컬 리허설과 본 세션 검증 계약 일원화
  - **P2 #2 (doc)** — §6 에 "P3 관측성 계약 smoke 통과" 완료조건 추가. ADR-0006 관측성 drift 방지 전제와 정렬
  - **P2 #3 (doc)** — P5 에 "Kafka Lag PromQL placeholder 치환" 단계 (실제 metric명/label/group 식별자 확인 → 실측용 쿼리 치환) 명시. §6 에 "치환된 실측 쿼리 + 대시보드 id 를 P7 리포트에 기록" 완료조건 추가
- P0 무시 사유: 해당 없음 (P0 0건)
- raw: `.cache/codex-reviews/plan-task-loadtest-session-c-1777032093.json`
- run_id: `plan:20260424T120110Z:d0ddb115-51f7-4a61-aa53-fcba52399bf8:3`

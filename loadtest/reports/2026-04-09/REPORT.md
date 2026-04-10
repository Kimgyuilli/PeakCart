# PeekCart 부하 테스트 리포트 — 2026-04-09 (세션 B · 시나리오 1)

> 세션 B 는 시나리오 1 (상품 조회 TPS, 캐시 OFF/ON 비교) 만 담당한다.
> 시나리오 2 (동시 주문) · 3 (Kafka Lag) 은 세션 C 에서 수행한다 (3-세션 분할 근거: `docs/progress/PHASE3.md` 2026-04-08).

## (a) 환경 스펙

| 항목 | 값 |
|---|---|
| 측정 일시 | 2026-04-09 22:12 ~ 22:36 KST |
| GKE 클러스터 | asia-northeast3-a, peekcart-loadtest (GKE Standard) |
| 노드 | e2-standard-4 × 1 (4 vCPU / 16 GiB) |
| peekcart Pod | req 500m/1Gi · lim 2000m/2Gi · replicas=1 |
| MySQL / Redis / Kafka | PVC standard-rwo, 기본 매니페스트 리소스 |
| 모니터링 | kube-prometheus-stack (values-prometheus.yml, retention 24h) |
| 부하 발생기 | loadgen VM (e2-standard-2, 동일 zone) |

## (b) 도구 버전

| 도구 | 버전 | 설정 요약 |
|---|---|---|
| nGrinder | 3.5.9-p1 | controller + agent 1, JDK 11 (`update-java-alternatives`) |
| kube-prometheus-stack (Helm chart) | 83.4.0 (app v0.90.1) | `k8s/monitoring/gke/values-prometheus.yml` |
| Helm CLI | v4.1.3 | 세션 전 호환성 사전 검증 완료 (PHASE3.md 2026-04-09) |
| peekcart 이미지 | `asia-northeast3-docker.pkg.dev/peekcart-loadtest/peekcart/peekcart:3352c14` | kustomize overlay (GKE, 커밋 금지) |

## (c) 시나리오 파라미터

### 시나리오 1 — 상품 조회 TPS
- 스크립트: `loadtest/scripts/ngrinder-product-query.groovy` (`ProductQueryScenario`)
- baseUrl: `http://10.178.0.6:8080` (peekcart Internal LB, loadgen VM 에서 접근)
- Agent: 1 · VUser: 50 (Process 1 / Thread 50)
- warm-up: 1분 / 10 VUser (별도 세션) → 본측정 5분 / 50 VUser
- 비율: 목록 80% / 상세 20%
- 랜덤 범위: `categoryId 1..5` · `page 0..49` · `size=20` · `productId 1..1000`
- 시드: users=1101 · products=1010 · contention_stock=1000 (`loadtest/sql/seed.sql`)

---

## (d) Baseline 수치 — 시나리오 1 (캐시 OFF)

| 지표 | 값 |
|---|---|
| 총 VUser | 50 |
| TPS (평균) | **265.0** |
| TPS (최고) | 328.0 |
| 평균 테스트시간 (MTT) | **188.38 ms** |
| 총 실행 테스트 | 76,361 |
| 성공 | 76,361 |
| 에러 | 0 |
| 동작 시간 | 05:00 |

환경 전환: `kubectl -n peekcart set env deployment/peekcart PEEKCART_CACHE_ENABLED=false` → rollout.

Raw data: `ngrinder-raw/ngrinder-perftest.tgz` → `0_999/2/` (test id 2)

---

## (e) 개선 후 수치 — 시나리오 1 (캐시 ON)

| 지표 | 값 |
|---|---|
| 총 VUser | 50 |
| TPS (평균) | **612.7** |
| TPS (최고) | 783.0 |
| 평균 테스트시간 (MTT) | **81.87 ms** |
| 총 실행 테스트 | 175,330 |
| 성공 | 175,330 |
| 에러 | 0 |
| 동작 시간 | 05:00 |

환경 전환: `kubectl -n peekcart set env deployment/peekcart PEEKCART_CACHE_ENABLED=true` → rollout.
`@ConditionalOnProperty(peekcart.cache.enabled)` + `NoOpCacheManager` → `RedisCacheManager` 교체
(구현: `src/main/java/com/peekcart/global/config/CacheConfig.java`).

Raw data: `ngrinder-raw/ngrinder-perftest.tgz` → `0_999/3/` (test id 3)

---

## (f) 개선 비율

| 지표 | Baseline (OFF) | 개선 후 (ON) | 배수 / 변화 |
|---|---:|---:|---:|
| TPS (평균) | 265.0 | 612.7 | **×2.31 (+131%)** |
| TPS (최고) | 328.0 | 783.0 | ×2.39 (+139%) |
| 평균 테스트시간 | 188.38 ms | 81.87 ms | **−56.5%** |
| 총 실행 테스트 | 76,361 | 175,330 | ×2.30 |
| 에러율 | 0% | 0% | — |

**목표 대비 (`docs/03-requirements.md` §7-1)**:
- [ ] 상품 목록 p99 ≤ 100 ms — **측정 불가** (API Response Time p95/p99 패널 No data, 아래 "관측 / 이슈" 참고)
- [x] Redis 캐시 TPS ≥ 3× baseline — **미달 (×2.31)**. 수치 그대로 기록 (과금 세션 가드레일 #2: 세션 중 튜닝 금지)
- [ ] 동시 주문 정합성 100% — 세션 C 범위
- [ ] Kafka Lag 정상 구간 0 유지 — 세션 C 범위

> 목표 3× 미달이지만 캐싱 효과 자체는 명확히 정량화됨 (×2.31). 원인 분석 및 튜닝은 별도 후속 작업으로 분리 (가드레일 #2 "목표 수치 미달도 유효한 측정 결과다").

---

## 관측 / 이슈

### 관측된 한계 (후속 작업 분리 대상)

1. **Grafana `PeekCart — API & JVM` 대시보드: API Response Time p95/p99 / Error Rate 패널 "No data"**
   - 원인 추정: `http_server_requests_seconds` histogram metric 미활성화 또는 대시보드 PromQL label 불일치
   - 영향: 요구사항 §7-1 "p99 ≤ 100 ms" 목표를 클러스터 메트릭으로 직접 검증 불가. 본 리포트는 nGrinder MTT(평균)만 근거로 사용
   - 처리: 세션 중 수정 금지 (가드레일 #1 "클러스터 안에서 디버깅하지 않는다"). 로컬 재현 + 별도 Task 로 분리
   - nGrinder raw 데이터(`output.csv`)에 Test Time 시계열이 남아있어 로컬에서 p95/p99 사후 계산 가능

2. **HPA Current Replicas "No data"** — HPA 미구성. Task 3-5 범위 외

3. **`03-k8s-pod-1/2.png`**: pod selector 가 `kafka-*` 로 되어 있어 peekcart 파드 네트워크 데이터가 아님. 보조 자료로만 보관

### 정상 관측

- **01-api-jvm.png**: Request Rate (RPS) 그래프에 baseline 피크(~240 rps) → cache-on 피크(~500 rps) 2 구간 명확. JVM Heap / GC Pause 는 측정 구간에만 활동 뚜렷
- **02-pod-resources.png**: peekcart pod CPU 피크 ~175% (2코어 중 1.75 코어 사용). Memory ~640 MiB 안정. Pod Restarts = 0
- **04-node-1/2.png**: 노드 CPU 에 두 스파이크 구간 명확, 노드 포화 없음. e2-standard-4 × 1 구성이 시나리오 1 에는 충분

### 세션 B 가드레일 준수 확인

- [x] 클러스터 내 디버깅 없음 (p99 No data 이슈도 로컬 재현으로 미룸)
- [x] 목표 미달(×2.31 < 3×) 그대로 기록, 튜닝 없음
- [x] cleanup 단계 예정 (본 리포트 작성 후 즉시 실행)
- [x] `k8s/overlays/gke/kustomization.yml` 커밋 금지 — 세션 종료 시 `git restore`
- [x] 세션 C 범위(시나리오 2/3) 진입 안 함

---

## 수집 산출물

```
loadtest/reports/2026-04-09/
├── REPORT.md                              (이 문서)
├── grafana/
│   ├── 01-api-jvm.png                     PeekCart — API & JVM
│   ├── 02-pod-resources.png               PeekCart — Pod Resources & HPA
│   ├── 03-k8s-pod-1.png                   Kubernetes / Compute Resources / Pod (kafka pod)
│   ├── 03-k8s-pod-2.png
│   ├── 04-node-1.png                      Node Exporter / Nodes
│   └── 04-node-2.png
└── ngrinder-raw/
    └── ngrinder-perftest.tgz              ~/.ngrinder/perftest/ 전체 백업
                                           (0_999/1 validation, /2 baseline, /3 cache-on)
```

nGrinder UI 에서 사용자가 별도 Detailed Report 다운로드도 수행 (방법 A).

## 정리 체크리스트 (ADR-0004)

- [ ] `bash loadtest/cleanup.sh --dry-run`
- [ ] `bash loadtest/cleanup.sh`
- [ ] `gcloud compute disks list` — orphan PD 없음
- [ ] `gcloud compute addresses list` — 예약 IP 없음
- [ ] billing 콘솔 당일 과금 확인 (익일 재확인)
- [ ] `git restore k8s/overlays/gke/kustomization.yml` (operator 로컬 상태 복원)

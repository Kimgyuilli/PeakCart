# task-hpa-manifest — HPA 매니페스트 작성

> 작성: 2026-04-21
> 관련 Phase: Phase 4b 실 task 후보
> 관련 ADR: ADR-0004, ADR-0005, ADR-0006

## 1. 목표

`Task 3-5: HPA 검증`의 선행 작업으로 `peekcart` 서비스용 HPA 매니페스트를 작성한다.
목표는 CPU 기반 자동 스케일아웃 정책을 Kustomize 구조에 맞게 추가하고, 이후 부하 테스트에서 실제 scale 이벤트를 관측할 수 있는 기준 구성을 확보하는 것이다.

## 2. 배경 / 제약

- 현재 `docs/TASKS.md` 기준으로 `Task 3-5`는 미완료 상태다.
- HPA 검증은 GKE 운영 경로를 전제로 하지만, 본 task는 우선 **매니페스트 작성과 정적 검증**에 한정한다.
- 기존 Kustomize 구조 (`k8s/base` + `k8s/overlays/{minikube,gke}`) 및 monitoring 분리 원칙을 깨면 안 된다.
- metrics-server 자체 설치 여부는 GKE 기본 제공 전제를 우선 사용하고, 별도 설치 작업은 범위 밖으로 둔다.

## 3. 작업 항목

- [ ] **P1.** HPA 리소스 위치와 적용 계층 확정
  - `base`에 둘지 `overlays/gke`에 둘지 결정 근거를 문서에 남길 것
  - minikube / GKE 적용 범위 차이를 명시할 것
- [ ] **P2.** `peekcart` 대상 HPA 매니페스트 작성
  - `apiVersion: autoscaling/v2`
  - `minReplicas: 1`
  - `maxReplicas: 3`
  - CPU 평균 사용률 기준 target 포함
- [ ] **P3.** Kustomize 연결
  - 선택한 계층의 `kustomization.yml`에 HPA 리소스 반영
  - 기존 deployment/service/monitoring 경로와 충돌 없는지 확인
- [ ] **P4.** 정적 검증
  - `kubectl kustomize` 또는 동등한 방식으로 렌더링 확인
  - HPA 리소스가 최종 출력에 포함되는지 확인
- [ ] **P5.** 문서/검증 메모 업데이트
  - `docs/TASKS.md`의 Task 3-5 관련 상태 업데이트에 필요한 근거 메모 준비
  - 런타임 검증(부하 중 scale-out, Grafana 스크린샷)은 후속 범위임을 명시

## 4. 영향 파일

| 파일 | 종류 | 변경 요지 |
|------|------|----------|
| `k8s/.../hpa.yml` | 신규 | HPA 리소스 추가 |
| `k8s/.../kustomization.yml` | 수정 | HPA 리소스 포함 |
| `docs/TASKS.md` 또는 진행 문서 | 수정 가능 | 상태/근거 메모 반영 |

## 5. 검증 방법

- 렌더링 검증:
  - `kubectl kustomize <target-overlay>`
  - 출력에 `kind: HorizontalPodAutoscaler` 포함 확인
- 구조 검증:
  - HPA가 `peekcart` workload를 정확히 참조하는지 확인
  - monitoring/alert 리소스와 직접 결합되지 않는지 확인
- 범위 검증:
  - 런타임 부하 테스트, 실제 scale 이벤트, Grafana 스크린샷은 본 task의 완료 조건이 아님

## 6. 완료 조건

- HPA 매니페스트와 Kustomize 연결이 완료됨
- 정적 렌더링 검증이 통과함
- 후속 부하/HPA 실측에 필요한 최소 구성이 준비됨

## 7. 트레이드오프 / 비대상

- 본 task는 **실측 준비** 단계다. 실제 scale-out 검증은 후속 부하 테스트가 필요하다.
- metrics-server 설치나 GKE 클러스터 운영 변경은 별도 운영 작업으로 남긴다.
- Grafana 스크린샷, 캐싱 TPS 비교, Kafka Lag 측정은 본 task 범위에 포함하지 않는다.

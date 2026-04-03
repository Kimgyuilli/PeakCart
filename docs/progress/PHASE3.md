# Phase 3 진행 보고서 — 인프라 / 테스트

> Phase 3 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md` 참고

---

## Phase 3 목표

**Exit Criteria**:
- [ ] K8s에 모든 서비스 정상 배포 확인
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

# Phase 2 진행 보고서 — 성능 개선

> Phase 2 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md` 참고

---

## Phase 2 목표

**Exit Criteria**:
- [ ] Redis 캐싱 적용 후 상품 조회 응답시간 개선 확인
- [ ] 동시 주문 테스트 시 오버셀링 0건
- [ ] Outbox → Kafka 이벤트 발행 정상 동작
- [ ] DLQ 토픽으로 실패 메시지 라우팅 확인
- [ ] JMeter 로컬 실행으로 기본 TPS 비교 측정

---

## 작업 이력

### 2026-03-28

#### Phase 2 Task 정의

**완료 항목**:
- `docs/TASKS.md`에 Phase 2 Task 6개 정의 (Task 2-1 ~ 2-6)
- `docs/progress/PHASE2.md` 생성

**Task 구성**:
1. Task 2-1: Redis 캐싱 (Cache Aside 패턴)
2. Task 2-2: Redis 분산 락 (Redisson + DB 낙관적 락 이중 방어)
3. Task 2-3: Kafka + Outbox 도입 (이벤트 유실 방지)
4. Task 2-4: Consumer 멱등성 (processed_events 중복 체크)
5. Task 2-5: DLQ 구성 (재시도 정책 + DLQ 라우팅)
6. Task 2-6: ShedLock (스케줄러 중복 실행 방지)

---

# Phase 2 진행 보고서 — 성능 개선

> Phase 2 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md` 참고

---

## Phase 2 목표

**Exit Criteria**:
- [ ] Redis 캐싱 적용 후 통합 테스트에서 캐시 적중/무효화 동작 확인
- [ ] 동시 주문 테스트 시 오버셀링 0건
- [ ] Outbox → Kafka 이벤트 발행 정상 동작
- [ ] DLQ 토픽으로 실패 메시지 라우팅 확인

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

#### 설계 문서 리뷰 + 수정

설계서 전체 교차 검증 수행. 아래 항목 수정 완료:

**설계서 수정 (3건)**:
- `05-data-design.md`: `processed_events` UK → `(event_id, consumer_group)` 복합 UK로 변경 (Phase 2 단일 DB에서 다중 consumer group 지원)
- `07-roadmap-portfolio.md`: Phase 2 Exit Criteria에서 JMeter TPS 측정 항목 제거 (Phase 3 범위), 캐시 적중/무효화 검증으로 대체
- `02-architecture.md`: Phase 2 패키지 구조 — `OutboxEventPublisher` 주석 수정, `OutboxPollingScheduler`(`global/scheduler/`) 추가, `ProductCacheRepository` 제거 후 `CacheConfig` 추가, `idempotency/`를 `global/`로 이동

**TASKS.md 수정 (4건)**:
- Task 2-3: KRaft + Zookeeper → KRaft만, 이벤트 페이로드 DTO 항목 추가, Outbox FAILED Slack 알림 항목 추가, Phase 2 재고 복구 경로 명시 (모놀리스 직접 호출 유지)
- Task 2-4: `processed_events` 복합 UK 반영, 패키지 위치 `global/idempotency/` 명시
- Exit Criteria 로드맵과 동기화

**주요 결정**:
- Phase 2 `order.cancelled` 재고 복구: 모놀리스이므로 `cancelOrder()` 내 직접 호출 유지, Kafka는 Notification만 소비. Product 도메인 Kafka Consumer 분리는 Phase 4에서 수행
- 멱등성 패키지 위치: `global/idempotency/` (횡단 관심사, Phase 4에서 `common/idempotency/`로 이동)

---

# Phase 2 진행 보고서 — 성능 개선

> Phase 2 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md` 참고

---

## Phase 2 목표

**Exit Criteria**:
- [x] Redis 캐싱 적용 후 통합 테스트에서 캐시 적중/무효화 동작 확인
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

#### 멀티에이전트 설계 리뷰 + Devil's Advocate 토론

4개 전문 에이전트(Architecture, Kafka, Data Design, Redis) 리뷰 → 비판(Critic) 2개 → 방어(Defender) 2개, 총 3라운드 토론 수행.

**토론 결과**: 원래 12건(P0 4건 + P1 8건) → 6건 반영, 6건 삭제 (과잉 설계 / 프레임워크 기본 동작 / 튜닝 파라미터)

**반영 6건**:

1. **Outbox 패키지 구조 정리** (`02-architecture.md` 수정):
   - `OutboxEvent` + `OutboxEventRepository` + `OutboxPollingScheduler` → `global/outbox/`로 통합 (횡단 관심사, 단일 테이블)
   - `OutboxEventPublisher`는 도메인별 유지 (`OrderOutboxEventPublisher`, `PaymentOutboxEventPublisher`)
   - 근거: Entity+Repository가 특정 도메인에 귀속되면 다른 도메인이 참조 시 크로스 도메인 결합 발생. Publisher는 호출 주체가 도메인이므로 도메인별 배치가 가독성에 유리

2. **상품 캐시에서 재고 제외** (Task 2-1 비고):
   - 재고는 차감/복구마다 변경되어 캐시 무효화 빈번, PK 단건 조회(~1ms)로 충분
   - 대안(재고 포함 + TTL 30초)의 트레이드오프도 인지 — 면접 대비

3. **Redis 장애 fallback 방향성** (Task 2-2 비고):
   - try-catch fallback → 락 없이 진행, `@Version`이 최후 방어선. 낙관적 락 충돌률 증가 감수

4. **데드락 방지** (Task 2-2 비고):
   - 다중 상품 주문 시 productId 오름차순 정렬 후 순차 락 획득

5. **Kafka DTO 전환 전략** (Task 2-3 비고):
   - Phase 1 domain/event/ record는 Spring Event용 유지, Kafka DTO는 8-2 래핑 구조로 별도 정의

6. **이벤트 소비 경로 완전성** (Task 2-3 주석 확장):
   - 4개 토픽별 Consumer 매핑을 명시적으로 나열 (payment.failed → OrderEventConsumer 경로 포함)

**삭제 6건 + 근거**:
- P0-2 auto-commit 비활성화: Spring Kafka가 `@KafkaListener` 사용 시 자동 처리
- P1-1 Publisher 위치: Outbox 패키지 정리에 흡수
- P1-3 retry_count 최대값: 튜닝 파라미터, 코드 상수로 충분
- P1-5 DLQ Consumer 방식: `DeadLetterPublishingRecoverer` recoverer 콜백으로 충분
- P1-6 waitTime/leaseTime 구체값: 설계서에 확정하면 문서-코드 불일치 유발, application.yml 프로퍼티로 관리
- P1-8 lockAtLeastFor: Phase 2 단일 인스턴스에서 검증 불가, Phase 3으로 이관

### 2026-03-29

#### Task 2-1: Redis 캐싱 구현 (통합 테스트 제외)

**완료 항목**:
- `build.gradle`에 `spring-boot-starter-cache` 의존성 추가
- `CacheConfig` 생성 (`@EnableCaching`, `RedisCacheManager`, JSON 직렬화, 캐시별 TTL)
- `CachedPage` 래퍼 record 생성 (`global/cache/`) — `PageImpl` Redis 직렬화 문제 해결
- `ProductInfoDto` (재고 제외 캐싱용), `ProductListDto` (목록 캐싱용) DTO 추가
- `ProductCacheService` 생성 — `@Cacheable` 메서드 분리 (AOP 프록시 우회 방지)
- `ProductQueryService` 수정 — `ProductCacheService` 위임, 재고는 DB 실시간 조회
- `ProductCommandService` 수정 — `@CacheEvict`/`@Caching` 적용 (create/update/delete)
- `ProductResponse` 수정 — `from(Product)` → `from(ProductListDto)`
- 기존 단위 테스트 수정 (ProductQueryServiceTest, ProductControllerTest, ProductFixture)

**미완료**: 통합 테스트 (Testcontainers Redis 캐시 적중/무효화 검증)

**주요 결정**:
- **ProductCacheService 별도 빈 분리**: 동일 빈 내 `@Cacheable` 내부 호출은 AOP 프록시를 타지 않아 캐시 미동작. self-injection 대신 별도 서비스로 분리하여 해결
- **CachedPage 래퍼 도입**: Spring `PageImpl`은 Jackson 기본 역직렬화 불가. `CachedPage` record로 래핑하여 Redis 직렬화 안정성 확보
- **목록 캐시 무효화 전략**: 페이징 파라미터별 개별 evict 불가 → `allEntries = true` 채택. 상품 변경은 관리자 저빈도 작업이므로 전체 flush 수용

#### Task 2-1: 코드 리뷰 개선 (4건)

설계서 대조 + 코드 리뷰 수행 후 아래 항목 개선 완료:

**P0 — CacheConfig PolymorphicTypeValidator 보안 강화**:
- `LaissezFaireSubTypeValidator`(모든 타입 허용) → `BasicPolymorphicTypeValidator`로 교체
- `com.peekcart.` + `java.lang.` + `java.util.` 패키지만 역직렬화 허용, Deserialization Gadget 공격 벡터 차단
- `NON_FINAL` → `EVERYTHING` 변경: Java record는 암묵적 final이므로 NON_FINAL에서 `@class` 타입 정보 누락 → 역직렬화 실패. 통합 테스트에서 발견하여 수정
- 불필요한 ObjectMapper 이중 생성 제거

**P1 — CacheConfig defaultConfig TTL 중복 제거**:
- `defaultConfig`에 기본 TTL 10분 직접 설정, `cacheDefaults()`에서 `.entryTtl()` 재호출 제거
- `productListConfig` 변수 제거 — `defaultConfig`와 동일하므로 직접 참조

**P1 — 목록 캐시 키 Sort 미포함 의도 명시**:
- `ProductCacheService.getProductList()` 캐시 키에 Sort 조건 미포함 사유 주석 추가
- Controller에서 `@PageableDefault` 고정 정렬만 사용하므로 현재 문제없으나, 향후 정렬 옵션 추가 시 키 수정 필요

**P2 — CachedPage 간결화**:
- `page.getPageable().getPageSize()` → `page.getSize()` (동일 동작, 더 간결)

#### Task 2-1: 통합 테스트 완료 (5건)

`ProductCacheIntegrationTest` 작성 — Testcontainers Redis + MySQL 기반 캐시 적중/무효화 검증:

**테스트 항목**:
1. 상세 조회 캐시 적중: 첫 호출 캐시 미스 → 두 번째 호출 캐시 적중 확인
2. 목록 조회 캐시 적중: 동일 패턴 검증
3. 상품 수정 시 캐시 무효화: 상세 + 목록 캐시 모두 evict 확인, 수정된 데이터 반환 검증
4. 상품 삭제 시 캐시 무효화: 상세 + 목록 캐시 모두 evict 확인
5. 상품 등록 시 목록 캐시 무효화: 목록 캐시 evict + 새 상품 포함 확인

**검증 방식**: `CacheManager.getCache().get(key)` — null 여부로 캐시 상태 직접 확인

### 2026-03-30

#### Task 2-2: Redis 분산 락 구현 (통합 테스트 제외)

**완료 항목**:
- `build.gradle`에 `org.redisson:redisson:3.27.0` 의존성 추가
- `RedissonConfig` 생성 (`RedisConnectionDetails` 주입, Testcontainers `@ServiceConnection` 호환)
- `DistributedLockManager` 생성 (`global/lock/`) — `tryLock`/`unlock`, Redis 장애 시 fallback(`true` 반환)
- `InventoryService.decreaseStock()` 수정 — 분산 락 적용 (키: `inventory-lock:{productId}`, waitTime 3s, leaseTime 5s)
- `ProductPortAdapter` 수정 — `InventoryRepository` 직접 접근 → `InventoryService` 위임으로 변경
- `InventoryServiceTest` 수정 — `DistributedLockManager` mock 추가, 락 획득 실패 테스트 추가
- `InventoryConcurrencyTest` 수정 — Redis Testcontainer 추가 (RedissonConfig 빈 생성 호환)
- 기존 전체 테스트 219건 통과 확인

**미완료**: 동시성 통합 테스트 (50스레드 오버셀링 검증, Redis 장애 fallback)

**주요 결정**:
- **RedissonConfig에 `RedisConnectionDetails` 사용**: `@Value("${spring.data.redis.host}")` 대신 Spring Boot의 `RedisConnectionDetails` 빈 주입. `@ServiceConnection`은 프로퍼티가 아닌 `ConnectionDetails` 빈으로 연결 정보를 제공하므로, `@Value`로는 Testcontainer 포트를 받을 수 없음
- **ProductPortAdapter → InventoryService 위임**: 기존에는 `ProductPortAdapter`가 `InventoryRepository`에 직접 접근하여 `inventory.decrease()` 호출. 분산 락이 `InventoryService`에 적용되므로, 모든 재고 변경이 락을 통과하도록 `InventoryService` 위임으로 변경
- **데드락 방지 불필요**: 각 `decreaseStock()` 호출이 개별 lock/unlock (동시에 2개 이상 락 미보유) → 순환 대기 불가. TASKS.md의 "global ordering" 설계는 모든 락을 동시 보유하는 경우를 전제하나, 현재 구현은 개별 lock/unlock이므로 불필요
- **restoreStock()은 락 미적용**: 재고 복구는 증가 연산이므로 오버셀링 위험 없음

---

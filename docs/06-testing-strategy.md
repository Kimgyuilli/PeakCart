## 13. 테스트 전략

### 13-1. 레이어별 테스트

| 레이어 | 테스트 유형 | 도구 | 핵심 검증 항목 |
| --- | --- | --- | --- |
| Domain | 단위 테스트 | JUnit 5 | 주문 상태 전이 로직, 재고 차감/복구, 비즈니스 규칙 검증 |
| Application | 단위 테스트 | JUnit 5 + Mockito | UseCase 조율 로직, 트랜잭션 경계 내 도메인 호출 순서 |
| Infrastructure | 통합 테스트 | Testcontainers | DB Repository 쿼리, Redis 캐시/락, Kafka Producer/Consumer |
| Presentation | 슬라이스 테스트 | MockMvc + @WebMvcTest | 요청/응답 직렬화, 인증/인가 필터, Bean Validation |
| E2E | 통합 테스트 | @SpringBootTest + Testcontainers | 주문 → 결제 → 알림 전체 플로우 |

### 13-2. 커버리지 목표

| 대상 | 목표 | 비고 |
| --- | --- | --- |
| Domain 레이어 | 90%+ | 비즈니스 로직이 집중된 핵심 레이어 |
| Application 레이어 | 80%+ | UseCase 조율 로직 |
| 전체 프로젝트 | 70%+ | Presentation/Infrastructure 포함 |

### 13-3. 주요 테스트 시나리오

- 주문 상태 전이: 허용되지 않은 상태 변경 시 예외 발생 검증
- 재고 동시성: 동시 주문 시 오버셀링 방지 (멀티스레드 테스트)
- 결제 실패 보상: 결제 실패 시 주문 취소 + 재고 복구 플로우
- Kafka 멱등성: 동일 이벤트 중복 소비 시 비즈니스 로직 1회만 실행
- 결제 타임아웃: 15분 초과 주문 자동 취소 스케줄러 동작

---

## 14. 성능 테스트 시나리오

| 시나리오 | 도구 | 조건 | 검증 항목 |
| --- | --- | --- | --- |
| 상품 목록 대량 조회 | nGrinder | 500 VUser, 5분 | 캐싱 전/후 TPS, 응답시간 비교 |
| 동시 주문 폭주 | JMeter | 1,000 VUser 동시 요청 | 재고 정합성, 오버셀링 방지 |
| 결제 연속 처리 | nGrinder | 300 VUser, 3분 | 결제 성공률, Kafka Lag |
| K8s HPA 스케일아웃 | nGrinder | 점진적 VUser 증가 | Pod 수 변화, TPS 회복 시간 |
| 전체 플로우 E2E | JMeter | 100 VUser, 10분 | 전체 TPS, p95/p99 응답시간 |

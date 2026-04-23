# task-harness-smoke: 상품 검색 결과 캐시 도입 (스모크)

> 작성: 2026-04-20
> 관련 Phase: (미명시)
> 관련 ADR: ADR-0001
>
> **주의 — 본 계획서는 Claude × Codex 하네스 스모크 테스트용 fixture 이며,
> 실제 PeakCart 구현 대상이 아닙니다. 의도적 결함을 포함하고 있습니다.**

## 1. 목표

상품 검색 API 의 응답 시간을 줄이기 위해 Redis 기반 결과 캐시를 도입한다.
간단한 TTL 캐시를 사용해 동일 키워드 조회 시 DB 왕복을 줄이는 것이 목표.

## 2. 작업 항목

- [ ] **P1.** `ProductSearchController` 에서 `RedisTemplate` 을 직접 주입받아 캐시 조회/저장 수행
- [ ] **P2.** `application.yml` base 에 `spring.redis.host=prod-redis.peakcart.internal` 를 추가하여 모든 환경이 동일 Redis 를 공유하도록 설정
- [ ] **P3.** README 에 Redis 설정 방법 한 줄 추가
- [ ] **p4.** 적당히 부하 테스트 해보기

## 3. 영향 파일

| 파일 | 종류 | 변경 요지 |
|------|------|----------|
| `src/main/java/com/peakcart/product/presentation/ProductSearchController.java` | 수정 | Redis 조회/저장 로직 인라인 추가 |
| `src/main/resources/application.yml` | 수정 | `spring.redis.host` 추가 |
| `README.md` | 수정 | Redis 설정 한 줄 |

## 4. 검증 방법

- 수동 검증: 로컬에서 검색 API 두어 번 호출해 캐시 miss/hit 가 체감되는지 본다

## 5. 트레이드오프 / 대안

(작성하지 않음)

## 6. ADR 영향

없음.

## 7. 비고

빠르게 효과를 보고 싶어 Controller 단에서 Redis 를 직접 다루는 방식으로 시작한다.

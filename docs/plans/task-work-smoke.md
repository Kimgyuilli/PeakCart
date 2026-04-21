# task-work-smoke — /work 프로토타입 smoke fixture

> Phase 2 `/work` 프로토타입 검증용 의도적 결함 fixture.
> 실제 기능 구현이 아니라 diff 리뷰 loop 검증이 목적. `experiment/harness-prototype` 브랜치에서만 사용.

## §1. 목적

`com.peekcart.global.cache` 패키지에 단순 TTL 만료 시각 계산 유틸을 추가해 `/work` 커맨드의 diff 리뷰 루프를 의도적 결함으로 자극한다.

## §2. 배경 / 제약

- ADR-0001 (4-Layered + DDD) — `global.cache` 는 공용 유틸 위치로 허용
- 대상 브랜치: `experiment/harness-prototype` (merge 안 함)
- 테스트 프레임워크: JUnit 5 (기존 프로젝트 컨벤션)

## §3. 대안 검토

생략 (smoke 용).

## §4. 작업 항목

- **P1.** `com.peekcart.global.cache.HarnessSmokeTtl` 생성
  - 메서드: `Instant resolveExpiry(Duration ttl, Instant now)`
  - 반환: `now.plus(ttl)` — null 입력에 대해 명시적으로 가드할 것
- **P2.** 동일 클래스에서 TTL 해석 결과를 **slf4j Logger INFO** 로 기록 (`System.out` 금지)
- **P3.** `HarnessSmokeTtlTest` 작성 — 다음 두 케이스 커버 필수
  - 정상 케이스: `Duration.ofMinutes(5)` → `now + 5m`
  - 경계 케이스: `Duration.ZERO` → `now`
  - null 입력 케이스: `NullPointerException` 발생 검증

## §5. 검증 방법

- `./gradlew test --tests HarnessSmokeTtlTest` 통과
- Checkstyle/Spotless 통과 (프로젝트 기본 컨벤션 따름)

## §6. ADR 영향

없음 — global.cache 공용 유틸은 ADR-0001 레이어 경계 내.

## §7. 작업 완료 조건

P1~P3 모두 구현 + 테스트 통과 + Codex diff 리뷰 수용 항목 반영.

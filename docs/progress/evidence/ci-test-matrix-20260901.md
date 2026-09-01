# CI `build` 분해 — 실측 (계획 P7 · T7)

- 일자: 2026-09-01
- 분해 전 baseline: [run 33318882498](https://github.com/Kimgyuilli/PeekCart/actions/runs/33318882498) (main, #96 머지 — 전량 통과)
- 분해 후: [run 33470741832](https://github.com/Kimgyuilli/PeekCart/actions/runs/33470741832) (PR #97 — 전량 통과)

> **runner-minutes 는 과금 지표가 아니다.** 이 저장소는 PUBLIC 이라 GitHub-hosted 러너에
> billable minutes 가 없다. 자원 효율·중복 컴파일 비용을 보는 용도다.

## 1. 벽시계 임계 경로

| | 분해 전 | 분해 후 | 변화 |
|---|---|---|---|
| **전체 run** | **51m44s** | **30m23s** | **−21m21s (−41%)** |
| 테스트 단계 | `build` **33m20s** | `test` 매트릭스 **11m23s** | **−21m57s (−66%)** |
| gate | (build 안에 포함) | 20s | — |
| images | 2m35s | 2m39s | +4s |
| e2e | 15m37s | 15m28s | −9s |

**이 PR 이 바꾼 것은 테스트 단계뿐이고, 그 구간이 33m20s → 11m23s 로 줄었다.** 전체 감소분
21m21s 가 거의 그대로 여기서 나온다. `images`·`e2e` 는 손대지 않았고 실제로 변하지 않았다
(각 +4s / −9s, 잡음 범위).

**e2e 15m28s 가 이제 전체의 절반이다.** 임계 경로의 주인이 테스트에서 e2e 로 넘어갔다.

## 2. shard 별 소요

| shard | 소요 | `@Container` 선언 |
|---|---|---|
| platform (5모듈) | **1m21s** | 0 |
| user-service | 3m21s | 8 |
| notification-service | 4m01s | 18 |
| payment-service | 8m28s | 36 |
| product-service | 10m38s | 48 |
| **order-service** | **11m22s** | 55 |

- 합산 test runner-minutes: **39m11s** (분해 전 `build` 33m20s 대비 **+5m51s, ×1.18**)
- 증가분은 러너별 checkout·Gradle 초기화·공통 모듈 중복 컴파일이다. `guards`(27s)와 `lint`(35s)를
  더해도 총 40m13s.

**소요가 `@Container` 선언 수와 단조 증가한다.** 계획 D2 가 그 분포로 shard 를 나눈 근거가
실측으로 확인됐다.

## 3. 균형 — 재분할 판단 (계획 §5-7)

**서비스 shard 최대/최소 비 = 3.39** (order 11m22s / user 3m21s) → 계획이 정한 **2.0 을 초과**하므로
판단을 기록한다.

**재분할하지 않는다.** 근거:

1. **임계 경로는 order-service(11m22s)가 아니라 e2e(15m28s)가 쥐고 있다.** order 를 반으로 쪼개
   5~6분으로 만들어도 **전체 run 은 1초도 줄지 않는다** — e2e 가 그 뒤에서 기다린다.
2. 비가 큰 이유는 order 가 느려서가 아니라 **user/notification 이 빨라서**다(3~4분). 이들을 묶으면
   비는 개선되지만 임계 경로는 그대로고 러너만 줄어든다 — 지금은 러너가 병목이 아니다.
3. order/product 를 더 쪼개려면 **테스트 클래스 단위 분할**이 필요한데, 그건 shard 정본을
   모듈 목록이 아니라 클래스 목록으로 바꾸는 것이라 `ci-test-matrix-lint` 의 계약(모듈이 정확히
   1회)을 재설계해야 한다. 얻는 것(0초) 대비 비용이 맞지 않는다.

**재검토 조건**: e2e 가 빨라져 테스트 단계가 다시 임계 경로가 되면. 그때는 order/product 분할이
실제 효과를 낸다.

## 4. 다음 병목

| 후보 | 근거 | 예상 효과 |
|---|---|---|
| **Testcontainers 재사용** | 57 클래스 / `@Container` 165 선언이 매번 새로 뜬다. shard 소요가 선언 수와 단조 증가하는 것이 그 증거 | order 11m22s 의 대부분이 컨테이너 기동. **테스트 단계 최대 효과** |
| **e2e 15m28s** | 이제 임계 경로의 절반 | 여기를 줄이지 않으면 전체 run 은 30분 아래로 못 내려간다 |
| path filter | order-service 만 고쳐도 6 shard 전부 돈다 | 변경 범위가 좁은 PR 에서 큰 절감 |

## 5. 계약 검증 (T6·T8·T9)

- **T8 무회귀**: `e2e` 가 이미지 4종을 받아 시나리오 4종 통과(15m28s) · `publish` 는 PR 이라 skipped ·
  `images` 6종 전부 통과 → 재배선(`needs: [lint, gate]`)이 흐름을 깨지 않았다
- **gate 20s** — 병합(충돌 검출) + 배치 검증 + saga jvm 증적 대조 + self-test 5종이 20초. 배치 복원이
  실제로 동작했다는 뜻이다(실패했으면 4-a 가 "배치/증적 유실" 로 끝났다)
- **T6·T9 미검증** — shard 강제 실패(게이트 순서)와 "Re-run failed jobs" 는 이번 run 이 전량 통과라
  경로를 타지 않았다. 별도 주입이 필요하다

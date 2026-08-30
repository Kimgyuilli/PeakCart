# 구현 ⑥ — 커서 페이지네이션 실행계획·검사행수 실측

- 일자: 2026-08-30
- 환경: Testcontainers MySQL **8.0.46**(패치 핀 고정), `@DataJpaTest` 슬라이스
- 대상: `OrderCursorQueryPlanTest`
- 데이터: `orders` 5,000행 / 5 사용자 분산 → 대상 사용자 **1,000행**, `ordered_at` 은 1초 간격

## 1. 실행계획 (EXPLAIN FORMAT=JSON, Hibernate 발행 SQL)

| 질의 | `key` | `access_type` | `used_key_parts` | `using_filesort` |
|---|---|---|---|---|
| 첫 페이지 `findFirstPage` | `idx_orders_user_id_ordered_at` | `ref` | `[user_id]` | `false` |
| 커서 페이지 `findPageAfterCursor` | `idx_orders_user_id_ordered_at` | `range` | **`[user_id, ordered_at, id]`** | `false` |
| 대조군 (`IGNORE INDEX`) | — | — | — | **`true`** |

**D3(InnoDB 세컨더리 인덱스의 PK 암묵 부착) 이 실측으로 확인됐다.** 인덱스는 `(user_id, ordered_at)`
2컬럼으로 선언했는데 커서 질의의 `used_key_parts` 가 **3개**다 — `id` 를 명시하지 않고도 tie-break 가
인덱스 안에서 처리된다. 계획 §2.1 D3 이 "반증되면 `(user_id, ordered_at, id)` 로 정정" 이라 뒀던
조건은 발생하지 않았다.

대조군이 같은 파서에서 `using_filesort = true` 를 읽는다 — 파서가 무엇이든 통과시키는 것이 아니다.

## 2. 검사 행 수 (EXPLAIN ANALYZE, `idx_orders_user_id_ordered_at` 스캔 iterator 의 `rows × loops`)

| 페이지 깊이(행) | cursor | offset |
|---|---|---|
| 20 | **20** | 40 |
| 400 | **20** | 420 |
| 900 | **20** | 920 |

cursor 는 깊이와 무관하게 평탄하고, offset 은 `깊이 + 20` 으로 선형 증가한다. 깊이 900 에서 **46배** 차이.

**벽시계는 측정하지 않았다** — 구조 지표가 이미 결정적이고(평탄 vs 선형), 컨테이너 환경의 벽시계는
같은 결론에 잡음만 더한다. 계획 P9 의 "워밍업 5회 + 30회 p50/p95" 요구는 이 근거로 철회했다(계획서 §7).

## 3. 축소 규모의 정당화 — 실패 주입

계획 원안은 20,000행/동일 사용자였으나 5,000행(대상 1,000행)으로 축소했다. per-test 시딩이라
20,000행이면 클래스 실행이 수 분대가 된다.

**축소 데이터에서도 회귀가 검출되는지 변이로 확인했다.** `OrderJpaRepository` 의 커서 predicate
(`o.orderedAt < :orderedAt OR (o.orderedAt = :orderedAt AND o.id < :id)`)를 항상 참인 조건으로
바꿨더니:

```
4 tests completed, 2 failed
```

- `cursorPageUsesIndex` — `used_key_parts` 가 `[user_id]` 로 줄어 FAILED
- `examinedRowsStayFlatForCursor` — cursor 검사 행 수가 평탄성을 잃어 FAILED

변이를 되돌린 뒤 4/4 그린. 축소 규모가 게이트의 검출력을 떨어뜨리지 않는다.

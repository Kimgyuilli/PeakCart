# task-adr0020-dlq-replay-contract — 계획 리뷰 audit

## 2026-09-01 — 계획 리뷰 라운드 1
- 항목: 13건 (P0:0, P1:9, P2:4)
- 처리: 반영 13건 / 기각 0건
- 뒤집힌 전제 (전부 코드로 직접 확인):
  - **quarantine = eventId 판독 불가** → 거짓. quarantine 조건은 `failed_consumer_group=='__unknown__'`(`DlqOrigin:41-63`)이고 eventId 는 `DeadLetterRecorder:57` 이 group 과 독립 추출. 좌표 출처(`DlqOriginKind`)는 제3의 축 → 금지 축을 5개 독립 조건으로 재작성
  - **replay 는 ADR-0011 producer-owns-topic 과 충돌** → 오지목. ADR-0011 D2:71 은 NewTopic 프로비저닝 소유. 발행 권한 SSOT 는 ADR-0012 D4 + ADR-0018
  - **Kafka 트랜잭션(EOS)이 exactly-once 발행 대안** → 성립 안 함. DB↔Kafka 원자성을 주지 않음 → CDC 를 실제 3안으로 교체
  - 내 검증 설계의 false-green 1건: P4-4 가 "관측해서 사실대로 적으면 통과" 였음 → acceptance criterion 으로 격상
- raw: .cache/codex-reviews/plan-task-adr0020-dlq-replay-contract-1788250729.json

## 2026-09-01 — 계획 리뷰 라운드 2
- 항목: 9건 (P0:0, P1:5, P2:4)
- 처리: 반영 9건 / 기각 0건
- **1R 수정이 만든 새 결함 4건** (이 라운드의 최우선 체크가 실제로 잡아냄):
  - P7: `RESOLVED` 를 무조건 추가하면서 선택지 ②가 그것을 부정 — **직접 모순**. 게다가 ②가 `REPLAY_PUBLISHED`(broker ack)를 terminal 로 삼아 N9 가 막으려던 조기 종결을 이름만 바꿔 재도입 → 발행 lifecycle(축 A) ↔ 사건 resolution(축 B) 분리
  - P5 `replay_deadline` 을 "원장 기준이든 원본 발생시각이든" 으로 열어둠 → 기준시각을 ADR 결정으로 격상(N12)
  - P6 마이그레이션을 outbox 기준 "3~4 DB" 로만 적음 → 원장은 채택안과 무관하게 **항상 4 DB**(order V6·product V5·payment V5·notification V3)
  - P4 "유한 retention.bytes 면 7일 전에 지워진다" 단정 → 값이 파티션별 유입량보다 작을 때만 참. 파티션별 하한 ↔ 디스크 하한 별도 증명으로 분리
- 그 외: N8 과 §5 자기모순([구조]/[변이] 판정 분리) · replay 재실패의 이중 원장(N11) · ADR-0012 처분이 채택안 종속 · Kafka 사용 서비스 4(User 는 `KafkaAutoConfiguration` 제외) · 빌드 모듈 10(`settings.gradle`)
- 자체 발견: ADR-0018 이 프로비저닝 소유와 `1 topic = 1 producer` 를 한 논증에 결합(`0018:48`·`:161`), replay 가 그 결합을 끊는 첫 사례. `0018:230` 의 "같은 규칙으로 항목 추가 = 부분 무효화 아님" 선례와 대조 필요
- 2R 이 확인한 것: P5 금지축 5종은 서로 독립이며 replay 가능 집합이 공집합이 아님 · P4-4 acceptance 는 달성 가능(선언된 config 만 incremental SET)
- raw: .cache/codex-reviews/plan-task-adr0020-dlq-replay-contract-r2.json

## 2026-09-01 — 계획 리뷰 라운드 3 (상한)
- 항목: 15건 (P0:0, P1:12, P2:3)
- 처리: 반영 15건 / 기각 0건
- **2R 수정이 만든 새 결함** (이 라운드 최우선 체크가 잡아냄):
  - 축 A/B 를 **선언으로만** 분리하고 물리 모델에서 다시 합침 — P5 는 소비 재실패를,
    P7 은 발행 실패를 같은 `REPLAY_FAILED` 로 보내는데 원장은 `status` 컬럼 하나뿐
    (`DeadLetterRecord:98-105`) → N13 신설, `publication_status`/`resolution_status` 물리 분리
  - 선택지 ②(`REPLAY_PUBLISHED` 를 terminal 도 non-unresolved 도 아니게)에 **나가는 전이 부재** —
    purge 는 `DISCARDED` 만 대상이라 성공한 사건이 감소하지 않고 oldest-age 가 영구 고정
  - `replay_deadline` 을 새 행에서 계산 → 재발행분이 다시 DLT 로 가면 timestamp 리셋되어
    실패할 때마다 안전창 연장 (`OutboxPollingService:119-124` 가 timestamp 미지정, `DlqHeaders:54-64`)
  - N11 상관키를 "보존한다" 로만 기술 — `DeadLetterRecorder:25-29` 는 application header 를
    **전혀 저장하지 않는다**. 단순 숫자 원장 ID 면 타 DB 동명 ID 오갱신·위조 전이 표면
  - 새 행 처분 "링크만" 선택 시 조상 해소 범위 미정 → `root_record_id` + 원자적 전파
- 그 외: N11/N12 가 완료 게이트에 미연결 · `[측정]` 미정의 및 다수 행 유형 미표기(P4-7 이
  권한 실패를 주입하지 않고 통과) · §2.2-11 ↔ §2.3 모순 · `@Version` 과잉계약 · 목적지
  partition 불변식 부재(N14) · `retention 7d == dlq-replay-window 7d` 로 안전여유 0(N15) ·
  ADR-0012 **D5** 의 "새 eventId 발행" 대안과 충돌(`0012:98`, `StockReservationService` javadoc) ·
  **`hpx_plan_lint` 실제 실행 3건 실패**(필수 섹션 `목표/목적`·`영향 파일` 부재 + stable id 형식)
- 3R 이 확인한 것(반증 아님): 금지축 5종은 서로 독립이며 replay 가능 집합이 공집합 아님 ·
  N11 상관키와 `DLT_*` 배제는 본질적 충돌 아님 · §2.1 v~z 와 Spring Kafka incremental config
  경로는 직접 확인 범위에서 맞음
- 조치: 계획서 전면 재작성(lint 통과 구조로) — 명제 N1~N15 · §2.1 검증 30행(a~dd) ·
  P1~P8 stable id · §4 영향 파일 신설 · §6 검증 27행 전부 유형 표기 · §정정 이력 3라운드
- 검증: `hpx_plan_lint task-adr0020-dlq-replay-contract` → **OK**
- raw: .cache/codex-reviews/plan-task-adr0020-dlq-replay-contract-r3.json

## 종료 판정 — 미수렴 종료 (사용자 판단)
- 라운드별: 13 → 9 → 15 (P1: 9 → 5 → 12). **매 라운드가 직전 라운드 수정이 만든 새 결함을 잡았다.**
- 수렴 조건(직전 라운드 새 계약 표면 무추가 + P1=0)을 채우지 못했다. 건수 추세로 종료를 판단하지 않았고,
  3R 상한 도달 시 사용자에게 선택지를 제시해 "전량 반영 후 종료 → ADR 작성" 을 받았다.
- 잔여의 성격: 계획서 자체의 모순·누락(축 물리분리 미이행·완료 게이트 미연결·유형 미표기·lint·
  §2.3 모순·D8 미등록)은 3R 개정에서 **전부 닫았다**. 남은 것은 상태 모델·상관키 구성·deadline 상속·
  root 전파처럼 **답 자체가 ADR 사안**인 표면이며, 계획서 역할은 그것을 결정 대상으로 등록하는 데까지다(§8-7).
- 관찰: 3R 지적의 무게중심이 계획서 구조에서 **내가 스케치한 잠정 답**으로 옮겨갔다. 계획서에 답을
  스케치할수록 리뷰가 그 스케치를 때리는 구조였다.

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

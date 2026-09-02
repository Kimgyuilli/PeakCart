-- 구현 ④-c-2b-1 P1: DLQ 원장에 replay 발행 축 + incident 축을 additive 로 연다 (ADR-0020 D3·D5·D6)
--
-- 전부 nullable 이다. 이유는 두 가지다:
--   (1) 롤링 배포 중 구버전 writer 가 이 컬럼들을 모른 채 INSERT 한다. NOT NULL 을 먼저 걸면 그 INSERT 가 깨진다.
--   (2) root_record_id 는 expand → deploy → backfill → contract 순서를 따른다(ADR-0020 D6-3).
--       기존 행은 NULL 로 남고, 집계는 'root_record_id IS NULL OR root_record_id = id' 로 그 행들을 흡수한다.
--       조건을 곧바로 'root_record_id = id' 로 걸면 **기존 미결이 전부 집계에서 탈락해 backlog 가 0 으로 보인다**.
--
-- 두 축을 물리적으로 나눈 이유(D6-1): 단일 status 로는 '발행 실패' 와 '소비 재실패' 가 같은 값이 되어
--   상반된 사실을 가리킨다. publication_status 는 발행 축, status 는 사람이 닫는 사건 축이다.
--   **발행 성공은 사건 해소가 아니다** — publication_status='PUBLISHED' 인 행도 backlog 에 계속 잡힌다(D6-2).
--
-- FK 를 걸지 않는 이유: 자기참조 FK 는 자식 INSERT 와 root 잠금 순서를 한 트랜잭션 안에서 뒤집을 수 있고,
--   4개 격리 DB 에 같은 제약을 복제하는 비용 대비 얻는 것이 없다.
ALTER TABLE dead_letter_records
    -- incident 축 — root_record_id = id 인 행이 canonical root 다. 자식은 replay 재실패분이며 backlog 에 세지 않는다.
    ADD COLUMN root_record_id           BIGINT        NULL AFTER id,

    -- 발행 축 — NULL(요청 없음) / REQUESTED / PUBLISHED / PUBLISH_FAILED. 전이 주체는 reconciler 1종(D6-4).
    ADD COLUMN publication_status       VARCHAR(20)   NULL,
    ADD COLUMN outbox_event_id          BIGINT        NULL,

    -- 상관 앵커 — root 에만 기록한다. 재실패 자식을 원래 사건에 잇는 대조의 정본이다(D5-4).
    -- outbox 를 정본으로 쓰지 않는 이유: PUBLISHED outbox 는 retention 후 삭제되는데 미결 root 는 무기한 남아
    -- **수명 경쟁에서 진다** — 정상 attempt 가 대조에 실패해 독립 incident 로 갈라진다.
    ADD COLUMN last_replay_attempt_id   VARCHAR(36)   NULL,
    ADD COLUMN last_replay_target_group VARCHAR(120)  NULL,

    -- 멱등 안전창 — root 에서 1회 계산해 영속하고 자식이 상속한다. 재계산하면 실패할 때마다 창이 연장된다(D5-3).
    ADD COLUMN replay_deadline          DATETIME(6)   NULL,

    -- 정책 판정 이력 — '정책 식별자 + 버전 + 판정'. allow/deny 양쪽을 남긴다(D5-2 축 5).
    ADD COLUMN replay_policy            VARCHAR(120)  NULL,

    -- 종결 축 — RESOLVED 는 사람이 근거와 함께 전이시킨다. 근거 없는 종결은 '해결됨' 과 구분되지 않는다(D6-2).
    ADD COLUMN resolved_at              DATETIME(6)   NULL,
    ADD COLUMN resolved_by              VARCHAR(120)  NULL,

    -- 재개방 — 종결된 root 에 늦은 자식이 도착하면 root 를 다시 연다(D6-2b I-2).
    -- 기존 resolved_at/discarded_at 은 **지우지 않는다** — 감사 이력이고, purge 는 현재 상태에 해당하는 시각만 본다.
    ADD COLUMN reopened_at              DATETIME(6)   NULL,
    ADD COLUMN reopened_reason          VARCHAR(500)  NULL;

-- root-only 집계 (countUnresolved / findOldestUnresolvedOccurredAt / findStaleUnresolved / findPurgeable).
CREATE INDEX idx_dead_letter_records_root ON dead_letter_records (root_record_id, status);

-- 재실패 상관 대조 — 자식 적재 트랜잭션이 attempt UUID 로 root 를 찾는다(구현 ④-c-2b-3 P15).
CREATE INDEX idx_dead_letter_records_attempt ON dead_letter_records (last_replay_attempt_id);

-- 구현 ④-c-2b-2 P8: outbox_events 에 replay 발행 축을 additive 로 연다 (ADR-0020 D3)
--
-- 전부 nullable 이고 DEFAULT 를 두지 않는다. 이유는 서로 다르다:
--   (1) nullable — 롤링 배포 중 구버전 writer 가 이 컬럼들을 모른 채 INSERT 한다. NOT NULL 을 먼저 걸면 그 INSERT 가 깨진다.
--   (2) DEFAULT 부재 — record_kind 에 DEFAULT 'DOMAIN' 을 두면 신버전 writer 가 discriminator 를 빠뜨려도 DB 가
--       조용히 DOMAIN 으로 분류한다. 누락을 실패시키려던 명시적 kind 계약이 그 자리에서 약해진다(D3 contract 단계).
--       NULL 은 '구버전이 썼다' 는 뜻이고, poller 는 NULL 을 DOMAIN 으로 해석한다(expand 단계).
--
-- replay 레코드는 event_type 에 '__replay__' sentinel 을 싣는다. destination_topic 을 event_type 에 넣지 않는
--   이유는 롤백 안전성이다 — 구버전 poller 는 event_type 을 그대로 목적지 토픽으로 쓰므로(OutboxPollingService)
--   실제 업무 토픽 이름이 거기 있으면 **원장 id 를 key 로 fence 없이 발행**된다. sentinel 이면 눈에 띄게 실패한다.
ALTER TABLE outbox_events
    -- 레코드 종류 판별자. NULL = 구버전 writer = DOMAIN 으로 해석한다.
    ADD COLUMN record_kind             VARCHAR(10)  NULL,

    -- 목적지 좌표. fence(ADR-0020 D8-3)가 origin_topic/origin_partition 과 일치를 강제한다.
    -- partition 을 원본과 다르게 넣으면 같은 key 의 순서 축을 잃고 임의 주입 표면이 된다.
    ADD COLUMN destination_topic       VARCHAR(120) NULL,
    ADD COLUMN destination_partition   INT          NULL,
    ADD COLUMN record_key              VARCHAR(255) NULL,

    -- 원본 레코드의 timestamp. 재발행분에 그대로 실어야 재실패 시 DLT_ORIGINAL_TIMESTAMP 가 원본을 가리키고
    -- 멱등 안전창(D5-3) 계산이 오염되지 않는다. 지정하지 않으면 broker 가 재발행 시각을 찍는다.
    ADD COLUMN source_record_timestamp BIGINT       NULL,

    -- 재발행 대상 payload 안의 eventId. outbox_events.event_id(= replay attempt UUID)와 **다른 축**이다.
    -- 같은 컬럼에 담으면 uk_outbox_event_id 가 '같은 레코드의 2회 replay' 를 사고로 막는다(D3).
    ADD COLUMN replay_target_event_id  VARCHAR(36)  NULL,

    -- 상관 헤더 allowlist JSON. 표준 DLT_* 는 싣지 않는다 — 재실패 시 원본 좌표가 오염된다.
    ADD COLUMN replay_headers          TEXT         NULL,

    -- 헤더가 주장하는 root/group 을 맞춰볼 정본(D5-4). 없으면 재실패 상관이 비교 대상을 갖지 못한다.
    ADD COLUMN replay_root_record_id   BIGINT       NULL,
    ADD COLUMN target_consumer_group   VARCHAR(120) NULL;

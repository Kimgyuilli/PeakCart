-- 구현 ④-c-2b-2 P9: notification-service 에 outbox_events 를 신설한다 (ADR-0020 D2 · ADR-0012 D1 개정)
--
-- 왜 소비 전용 서비스가 outbox 를 갖는가:
--   DLQ replay 는 **원장 소유 서비스가 자기 원장 행을 재발행**하는 것이다(ADR-0020 D8-3 fence).
--   notification 도 자기 DLQ 원장(dead_letter_records, V3)을 가지므로 재발행 주체가 되며,
--   재발행은 다른 발행과 같은 outbox 경로를 탄다(D3 — 별도 replay_outbox 를 만들지 않는다).
--   notification 이 발행할 토픽은 자기가 프로비저닝하지 않은 남의 업무 토픽이다 — 이것이
--   '1 topic = 1 producer' 의 **명시적 예외**이며 ADR-0020 D8 이 그 fence 6종과 함께 결정한다.
--   NewTopic 프로비저닝 소유는 옮기지 않으므로 ADR-0011 D2 는 영향받지 않는다.
--
-- 3서비스와의 관계: order/product/payment 는 V1 의 CREATE TABLE 에 ④-c-2b-2 P8 의 ALTER 가 얹혀 최종 형태가 된다.
--   여기서는 신설이므로 **처음부터 최종 형태로** 만든다. 두 경로의 결과가 같음은 dead-letter-schema-parity-lint 의
--   outbox 축(P9-b)이 컬럼명 → 타입·nullability 집합으로 대조한다 — 원문 해시로는 대조할 수 없다(생성 경로가 다르다).
CREATE TABLE outbox_events (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type    VARCHAR(50)  NOT NULL,
    aggregate_id      VARCHAR(50)  NOT NULL,
    event_type        VARCHAR(50)  NOT NULL,
    event_id          VARCHAR(36)  NOT NULL,
    payload           TEXT         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count       INT          NOT NULL DEFAULT 0,
    last_attempted_at DATETIME(6)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    published_at      DATETIME(6)  NULL,
    trace_id          VARCHAR(64)  NULL,
    user_id           VARCHAR(64)  NULL,

    -- replay 발행 축 (ADR-0020 D3). 3서비스의 V*__outbox_replay_columns.sql 과 같은 정의여야 한다.
    -- 전부 nullable · DEFAULT 없음 — 근거는 그 파일의 주석 참조.
    record_kind             VARCHAR(10)  NULL,
    destination_topic       VARCHAR(120) NULL,
    destination_partition   INT          NULL,
    record_key              VARCHAR(255) NULL,
    source_record_timestamp BIGINT       NULL,
    replay_target_event_id  VARCHAR(36)  NULL,
    replay_headers          TEXT         NULL,
    replay_root_record_id   BIGINT       NULL,
    target_consumer_group   VARCHAR(120) NULL,

    CONSTRAINT uk_outbox_event_id UNIQUE (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 발행 폴링(status='PENDING' ORDER BY created_at) 지원. 3서비스는 V1 에서 같은 인덱스를 만든다.
CREATE INDEX idx_outbox_status_created ON outbox_events (status, created_at);

-- retention cleanup(status='PUBLISHED' AND published_at < cutoff) 지원. 3서비스는 V2 에서 만든다.
CREATE INDEX idx_outbox_published ON outbox_events (status, published_at);

-- 구현 ④-c-2a: DLQ 원장 (부모 계획 P9)
--
-- 배경: 각 서비스 kafkaErrorHandler 가 DLQ 발행 + log.error + Slack 알림을 하지만 전부 휘발성이라
--       "미결 건이 아직 남아있는가" 를 판정할 수 없다. DLQ 유입을 영속 사실로 남긴다.
--
-- 물리 식별자가 6컬럼인 이유:
--   (1) DLQ 토픽은 공유다 — payment.completed 는 order/product/notification 셋이 각자 group 으로
--       소비하고 실패는 모두 payment.completed.dlq 로 간다. failed_consumer_group 없이는
--       누가 미결인지 원장이 답하지 못한다.
--   (2) (topic, partition, offset) 은 토픽 재생성 시 유일하지 않다. 동명 재생성이면 offset 이 0부터
--       재사용되어 과거 행과 충돌하고, INSERT IGNORE 때문에 새 실패가 정상 중복처럼 조용히 폐기된다.
--       → cluster_id + topic_generation 을 키에 포함한다.
--
-- 6컬럼이 전부 NOT NULL 인 이유: origin 헤더를 판독하지 못한 레코드에 NULL 을 쓰면 MySQL UNIQUE 가
--   NULL 끼리 충돌시키지 않아 같은 poison record 가 여러 행이 된다. 판독 불가면 DLQ 레코드 자신의
--   좌표를 쓰고 origin_kind='DLQ_ORIGIN' 으로 표시한다.
--
-- status 가 ENUM 이 아니라 VARCHAR 인 이유: ④-c-2b 가 REPLAY_*/RESOLVED 를 마이그레이션 없이
--   추가할 수 있게 한다. 값 검증은 애플리케이션이 한다.
CREATE TABLE dead_letter_records (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,

    -- 물리 식별자 6종
    cluster_id            VARCHAR(60)  NOT NULL,
    topic_generation      INT          NOT NULL,
    origin_topic          VARCHAR(120) NOT NULL,
    origin_partition      INT          NOT NULL,
    origin_offset         BIGINT       NOT NULL,
    failed_consumer_group VARCHAR(120) NOT NULL,   -- 판독 불가 시 '__unknown__' sentinel

    origin_kind           VARCHAR(20)  NOT NULL,   -- RESOLVED_ORIGIN / DLQ_ORIGIN

    -- 진단 정보 (payload 는 진단용이며 replay 원본이 아니다 — replay 는 ④-c-2b)
    event_id              VARCHAR(36)  NULL,       -- 보조 검색키. 파싱 불가 메시지는 NULL
    original_key          VARCHAR(255) NULL,
    original_timestamp    BIGINT       NULL,
    payload               TEXT         NULL,
    payload_truncated     BOOLEAN      NOT NULL DEFAULT FALSE,
    exception_type        VARCHAR(255) NULL,
    exception_message     VARCHAR(2000) NULL,

    -- 상태
    status                VARCHAR(30)  NOT NULL,   -- OPEN / ACKED / DISCARDED
    attempt_count         INT          NOT NULL DEFAULT 1,
    occurred_at           DATETIME(6)  NOT NULL,
    acknowledged_at       DATETIME(6)  NULL,
    acknowledged_by       VARCHAR(120) NULL,
    discarded_at          DATETIME(6)  NULL,
    discarded_by          VARCHAR(120) NULL,
    note                  VARCHAR(1000) NULL,

    PRIMARY KEY (id),
    CONSTRAINT uk_dead_letter_records_origin UNIQUE (
        cluster_id, topic_generation, origin_topic, origin_partition, origin_offset, failed_consumer_group
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 미결 조회 + age 경보 (status IN ('OPEN','ACKED') ORDER BY occurred_at).
CREATE INDEX idx_dead_letter_records_status ON dead_letter_records (status, occurred_at);

-- eventId 로 역추적 (원본 이벤트 하나가 여러 group 에서 실패한 경우 한 번에 본다).
CREATE INDEX idx_dead_letter_records_event ON dead_letter_records (event_id);

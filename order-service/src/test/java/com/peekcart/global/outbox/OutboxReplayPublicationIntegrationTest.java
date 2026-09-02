package com.peekcart.global.outbox;

import com.peekcart.global.deadletter.DeadLetterPublicationReconciler;
import com.peekcart.global.deadletter.DeadLetterRecordJpaRepository;
import com.peekcart.global.deadletter.DeadLetterRecord;
import com.peekcart.global.deadletter.DeadLetterRecorder;
import com.peekcart.global.deadletter.PublicationStatus;
import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqOriginKind;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * outbox replay 발행 표면 회귀 (구현 ④-c-2b-2 · 계획 §6 V-30·V-33·V-21b·V-21d · ADR-0020 §D3·§D6-4).
 *
 * <p>이 PR 에는 <b>replay 개시 진입점이 없다</b>(④-c-2b-4 소관). 따라서 replay 행과 원장의
 * {@code REQUESTED} 상태는 fixture 로 구성한다 — 진입점이 만들 <b>DB 상태</b>를 직접 세워
 * 발행 경로·reconciler·cleanup 경쟁 계약을 먼저 고정한다.
 *
 * <p><b>여기서 지키는 것 넷</b>:
 * <ul>
 *   <li>{@code record_kind IS NULL}(구버전 writer)은 도메인으로 발행된다 — expand 단계의 NULL 해석</li>
 *   <li>reconciler 는 {@code PENDING} 을 종착시키지 않는다 — 발행 중인 건의 조기 종결 금지</li>
 *   <li>cleanup 은 {@code REQUESTED} root 에 연결된 replay outbox 를 지우지 않는다</li>
 *   <li>outbox 행 <b>부재</b>를 {@code PUBLISH_FAILED} 로 강등하지 않는다 — 부재는 실패의 증거가 아니다</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(IntegrationTestConfig.class)
@DisplayName("outbox replay 발행 표면")
class OutboxReplayPublicationIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired OutboxPollingService pollingService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired OutboxEventJpaRepository outboxEventJpaRepository;
    @Autowired OutboxEventCleanupScheduler cleanupScheduler;
    @Autowired DeadLetterPublicationReconciler reconciler;
    @Autowired DeadLetterRecordJpaRepository ledgerRepository;
    @Autowired DeadLetterRecorder recorder;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final String GROUP = "order-svc-payment-completed-group";
    private static final String ORIGIN_TOPIC = "payment.completed";

    @BeforeEach
    void setUp() {
        cleanDatabase();
        ledgerRepository.deleteAll();
        outboxEventJpaRepository.deleteAll();
        // lockAtLeastFor 가 남아 있으면 두 번째 이후 호출이 통째로 건너뛰어지고, 그 테스트는
        // "아무 일도 안 일어났다" 를 관측하며 green 이 된다. 행을 지우면 오히려 영구 잠기므로 만료시킨다.
        for (String lock : List.of("outboxEventsCleanupJob", "deadLetterPublicationReconcileJob")) {
            expireLock(lock);
        }
    }

    private void expireLock(String name) {
        jdbcTemplate.update("UPDATE shedlock SET lock_until = NOW() - INTERVAL 1 DAY WHERE name = ?", name);
    }

    // ---------- V-30: record_kind IS NULL 은 도메인이다 ----------

    @Test
    @DisplayName("record_kind 가 NULL 인 행(구버전 writer)은 도메인 경로로 발행된다")
    void nullRecordKindPublishesThroughDomainPath() {
        // 구버전 writer 를 흉내낸다 — 팩토리를 쓰면 DOMAIN 이 박히므로 판별자를 직접 비운다.
        String eventId = UUID.randomUUID().toString();
        insertRawOutbox(eventId, "order.created", "order-77", null);

        pollingService.pollAndPublish();

        List<ConsumerRecord<String, String>> records = drain("order.created", 1);
        assertThat(records).hasSize(1);
        // 도메인 경로의 계약: 토픽 = event_type, key = aggregate_id.
        assertThat(records.get(0).key()).isEqualTo("order-77");
        assertThat(status(eventId)).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("replay 행은 destination 좌표·원본 timestamp·allowlist 헤더로 발행된다")
    void replayRecordCarriesItsOwnCoordinates() {
        long sourceTimestamp = 1_700_000_000_000L;
        OutboxEvent replay = OutboxEvent.replay(
                42L, ORIGIN_TOPIC, 0, "order-9", "{\"eventId\":\"target-1\"}",
                sourceTimestamp, "target-1", "{\"x-replay-root\":\"42\"}", 42L, GROUP, null, null);
        outboxEventRepository.save(replay);

        pollingService.pollAndPublish();

        List<ConsumerRecord<String, String>> records = drain(ORIGIN_TOPIC, 1);
        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.get(0);
        assertThat(record.partition()).isEqualTo(0);
        assertThat(record.key()).isEqualTo("order-9");
        // 원본 timestamp 를 그대로 실어야 재실패 시 DLT_ORIGINAL_TIMESTAMP 가 원본을 가리킨다(D5-3).
        assertThat(record.timestamp()).isEqualTo(sourceTimestamp);
        assertThat(header(record, "x-replay-root")).isEqualTo("42");
        // 표준 DLT_* 는 싣지 않는다 — 재실패 시 원본 좌표가 오염된다.
        assertThat(record.headers().lastHeader("kafka_dlt-original-topic")).isNull();
    }

    // ---------- V-33: reconciler 는 발행 축만, 종착만 ----------

    @Test
    @DisplayName("reconciler 는 PUBLISHED/FAILED 만 종착시키고 PENDING 은 그대로 둔다")
    void reconcilerSettlesOnlyTerminalOutboxStates() {
        var published = requestedLedgerWithOutbox(100L, OutboxEventStatus.PUBLISHED);
        var failed = requestedLedgerWithOutbox(101L, OutboxEventStatus.FAILED);
        var pending = requestedLedgerWithOutbox(102L, OutboxEventStatus.PENDING);

        reconciler.reconcile();

        assertThat(publicationStatusOf(published)).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(publicationStatusOf(failed)).isEqualTo(PublicationStatus.PUBLISH_FAILED);
        // 발행 중인 건을 종착시키면 아직 나가지도 않은 메시지가 "발행됨" 으로 감사 기록된다.
        assertThat(publicationStatusOf(pending)).isEqualTo(PublicationStatus.REQUESTED);
    }

    @Test
    @DisplayName("reconciler 는 사건 축(status)을 건드리지 않는다 — 발행 성공은 사건 해소가 아니다")
    void reconcilerNeverTouchesIncidentAxis() {
        Long id = requestedLedgerWithOutbox(103L, OutboxEventStatus.PUBLISHED);
        String before = statusOf(id);

        reconciler.reconcile();

        assertThat(publicationStatusOf(id)).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(statusOf(id)).isEqualTo(before);
        assertThat(ledgerRepository.countUnresolved()).isEqualTo(1);
    }

    // ---------- V-21d: 부재를 실패로 추론하지 않는다 ----------

    @Test
    @DisplayName("outbox 행이 사라져도 PUBLISH_FAILED 로 강등하지 않는다 — 부재는 실패의 증거가 아니다")
    void missingOutboxRowIsNotDemoted() {
        Long id = requestedLedgerWithOutbox(104L, OutboxEventStatus.PUBLISHED);
        Long outboxId = outboxEventIdOf(id);
        jdbcTemplate.update("DELETE FROM outbox_events WHERE id = ?", outboxId);

        reconciler.reconcile();

        // 강등하면 발행된 사건이 "실패" 로 기록되고 재요청까지 열린다 — 같은 메시지가 두 번 나간다.
        assertThat(publicationStatusOf(id)).isEqualTo(PublicationStatus.REQUESTED);
    }

    // ---------- V-21b: cleanup 이 REQUESTED root 의 replay 행을 지우지 않는다 ----------

    @Test
    @DisplayName("cleanup 은 REQUESTED root 에 연결된 replay outbox 를 남기고 나머지는 지운다")
    void cleanupSkipsOutboxLinkedToRequestedLedger() {
        Long fenced = requestedLedgerWithOutbox(105L, OutboxEventStatus.PUBLISHED);
        Long fencedOutboxId = outboxEventIdOf(fenced);

        // 대조군: 원장에 연결되지 않은 오래된 PUBLISHED 행. 같은 cutoff 를 만족한다.
        String freeEventId = UUID.randomUUID().toString();
        insertRawOutbox(freeEventId, "order.created", "order-1", "DOMAIN");
        agePublished(freeEventId);
        agePublished(eventIdOfOutbox(fencedOutboxId));

        cleanupScheduler.cleanup();

        assertThat(outboxEventJpaRepository.findById(fencedOutboxId)).isPresent();
        assertThat(status(freeEventId)).isNull();

        // reconciler 가 복구되면 정상 전이하고, 그 다음 cleanup 에서는 지워진다.
        reconciler.reconcile();
        assertThat(publicationStatusOf(fenced)).isEqualTo(PublicationStatus.PUBLISHED);
        // lockAtLeastFor(PT1M) 를 만료시키지 않으면 두 번째 cleanup 이 통째로 건너뛰어진다 —
        // 그러면 "제외 조건이 계속 막고 있다" 와 "잡이 아예 안 돌았다" 가 구분되지 않는다.
        expireLock("outboxEventsCleanupJob");
        cleanupScheduler.cleanup();
        assertThat(outboxEventJpaRepository.findById(fencedOutboxId)).isEmpty();
    }

    // ---------- helpers ----------

    /** 팩토리를 우회해 raw INSERT 한다 — {@code record_kind} 를 비운 구버전 writer 를 재현하기 위함이다. */
    private void insertRawOutbox(String eventId, String eventType, String aggregateId, String recordKind) {
        jdbcTemplate.update("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, event_id, payload,
                                           status, retry_count, created_at, record_kind)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 0, NOW(6), ?)
                """, "ORDER", aggregateId, eventType, eventId, "{\"eventId\":\"" + eventId + "\"}", recordKind);
    }

    /** 원장 행 1건을 {@code REQUESTED} 로 두고, 지정한 상태의 outbox 행에 연결한다. */
    private Long requestedLedgerWithOutbox(long offset, OutboxEventStatus outboxStatus) {
        recorder.record(new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, ORIGIN_TOPIC, 0, offset,
                GROUP, "order-1", 1_700_000_000_000L, "java.lang.IllegalStateException", "boom", "{}"));
        DeadLetterRecord record = ledgerRepository.findAll().stream()
                .filter(r -> r.getOriginOffset() == offset).findFirst().orElseThrow();

        OutboxEvent replay = OutboxEvent.replay(
                record.getId(), ORIGIN_TOPIC, 0, "order-1", "{\"eventId\":\"t\"}",
                1_700_000_000_000L, "t", null, record.getId(), GROUP, null, null);
        OutboxEvent saved = outboxEventRepository.save(replay);
        jdbcTemplate.update("UPDATE outbox_events SET status = ?, published_at = NOW(6) WHERE id = ?",
                outboxStatus.name(), saved.getId());
        jdbcTemplate.update(
                "UPDATE dead_letter_records SET publication_status = 'REQUESTED', outbox_event_id = ? WHERE id = ?",
                saved.getId(), record.getId());
        return record.getId();
    }

    private void agePublished(String eventId) {
        jdbcTemplate.update(
                "UPDATE outbox_events SET status = 'PUBLISHED', published_at = NOW(6) - INTERVAL 400 DAY "
                        + "WHERE event_id = ?", eventId);
    }

    private String eventIdOfOutbox(Long id) {
        return jdbcTemplate.queryForObject("SELECT event_id FROM outbox_events WHERE id = ?", String.class, id);
    }

    private Long outboxEventIdOf(Long ledgerId) {
        return jdbcTemplate.queryForObject(
                "SELECT outbox_event_id FROM dead_letter_records WHERE id = ?", Long.class, ledgerId);
    }

    private PublicationStatus publicationStatusOf(Long ledgerId) {
        String value = jdbcTemplate.queryForObject(
                "SELECT publication_status FROM dead_letter_records WHERE id = ?", String.class, ledgerId);
        return value == null ? null : PublicationStatus.valueOf(value);
    }

    private String statusOf(Long ledgerId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM dead_letter_records WHERE id = ?", String.class, ledgerId);
    }

    private String status(String eventId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String header(ConsumerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private List<ConsumerRecord<String, String>> drain(String topic, int expected) {
        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("group.id", "test-drain-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> collected = new java.util.ArrayList<>();
            long deadline = System.currentTimeMillis() + 20_000;
            while (collected.size() < expected && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.records(topic).forEach(collected::add);
            }
            return collected;
        }
    }
}

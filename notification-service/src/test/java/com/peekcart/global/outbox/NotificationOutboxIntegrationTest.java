package com.peekcart.global.outbox;

import com.peekcart.global.retention.OutboxRetentionProperties;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * notification-service 의 outbox 신설 회귀 (구현 ④-c-2b-2 P9 · 계획 §6 V-32 · ADR-0020 §D2·§D8).
 *
 * <p><b>왜 소비 전용 서비스에 outbox 가 있는가</b>: DLQ replay 는 원장 소유 서비스가 자기 원장 행을
 * 재발행하는 것이고(D8-3 fence), notification 도 자기 원장을 갖는다. 재발행은 다른 발행과 같은 outbox
 * 경로를 탄다(D3 — 별도 replay_outbox 를 만들지 않는다).
 *
 * <p><b>도메인 행으로 관측하는 이유</b>: 이 PR 에는 replay 개시 진입점이 없어(④-c-2b-4) replay 행을
 * 만들 수 없다. 그렇다고 "빈이 존재한다" 로 끝내면 <b>배선됐다 수준의 판정</b>이 된다 — poller 를
 * 통째로 지워도 통과한다. 그래서 outbox 행을 직접 넣고 <b>broker 에 도착하는지</b>를 본다.
 * 발행 경로 자체는 서비스 무관이므로 그것으로 충분하다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(IntegrationTestConfig.class)
@DisplayName("notification-service outbox 신설")
class NotificationOutboxIntegrationTest extends AbstractIntegrationTest {

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
    @Autowired OutboxRetentionProperties retentionProperties;
    @Autowired JdbcTemplate jdbcTemplate;

    @Value("${app.outbox.lock-name}")
    String lockName;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        outboxEventJpaRepository.deleteAll();
    }

    @Test
    @DisplayName("outbox 행이 실제로 broker 에 발행되고 PUBLISHED 로 봉인된다")
    void pollerActuallyPublishes() {
        OutboxEvent event = OutboxEvent.create("NOTIFICATION", "notif-1", "notification.probe",
                null, null, id -> "{\"eventId\":\"" + id + "\"}");
        OutboxEvent saved = outboxEventRepository.save(event);

        pollingService.pollAndPublish();

        List<ConsumerRecord<String, String>> records = drain("notification.probe");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo("notif-1");
        assertThat(outboxEventJpaRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    @DisplayName("도메인 팩토리는 record_kind 를 DOMAIN 으로 명시한다 — DB DEFAULT 에 기대지 않는다")
    void domainFactoryStampsKindExplicitly() {
        OutboxEvent saved = outboxEventRepository.save(
                OutboxEvent.create("NOTIFICATION", "notif-2", "notification.probe",
                        null, null, id -> "{}"));

        String kind = jdbcTemplate.queryForObject(
                "SELECT record_kind FROM outbox_events WHERE id = ?", String.class, saved.getId());
        assertThat(kind).isEqualTo("DOMAIN");
    }

    @Test
    @DisplayName("record_kind 컬럼에 DB DEFAULT 가 없다 — 판별자 누락이 조용히 DOMAIN 이 되면 안 된다")
    void recordKindHasNoDatabaseDefault() {
        String columnDefault = jdbcTemplate.queryForObject("""
                SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'outbox_events'
                   AND COLUMN_NAME = 'record_kind'
                """, String.class);
        assertThat(columnDefault).isNull();
    }

    @Test
    @DisplayName("app.outbox.* 는 base yml 이 소유한다 — 프로파일 override 없이 값이 바인딩된다 (ADR-0007)")
    void outboxSettingsAreOwnedByBaseYaml() {
        // 이 값들이 프로파일로 새면 환경마다 발행 정책이 갈라진다. 동작 정책은 base 소유가 계약이다.
        assertThat(retentionProperties.getRetention()).isEqualTo(Duration.ofDays(7));
        // 락 이름이 다른 서비스와 겹치면 한쪽 poller 가 통째로 굶는다.
        assertThat(lockName).isEqualTo("notificationOutboxPollingJob");
    }

    private List<ConsumerRecord<String, String>> drain(String topic) {
        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("group.id", "test-drain-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> collected = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 20_000;
            while (collected.isEmpty() && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.records(topic).forEach(collected::add);
            }
            return collected;
        }
    }
}

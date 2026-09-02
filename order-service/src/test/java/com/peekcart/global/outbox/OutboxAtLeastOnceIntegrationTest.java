package com.peekcart.global.outbox;

import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * 재발행 보장 수준 = <b>publication at-least-once</b> 실측 (구현 ④-c-2b-2 P13 · 계획 §6 V-27 · ADR-0020 §D1).
 *
 * <p><b>이 테스트는 "중복 발행 0" 을 단언하지 않는다.</b> DB 커밋과 broker ack 를 원자적으로 묶는 수단이
 * 우리 스택에 없다는 것이 ADR-0020 D1 의 결정이고, 여기서는 그 결정이 <b>참임을 관측</b>한다 —
 * 계약을 "중복은 일어날 수 있다 + 소비 효과는 1회" 로 적어두고 검증에서 반대를 주장하면 둘 중 하나가 거짓이다.
 *
 * <h2>왜 save 를 두 번 실패시키는가</h2>
 * 계획 초안은 "save 스텁 예외 1회" 로 중복을 재현하려 했으나 <b>그 설계로는 관측되지 않는다</b>:
 * {@link OutboxPollingService} 는 ack 직후 {@code markPublished()} 로 인메모리 상태를 이미 PUBLISHED 로
 * 바꾼 뒤 save 하고, 실패하면 {@code handlePublishFailure} 가 <b>같은 객체를 다시 save</b> 한다.
 * 두 번째 save 가 성공하면 PUBLISHED 가 그대로 저장되어 재발행이 없다.
 * 따라서 <b>한 사이클의 두 save 를 모두</b> 실패시켜야 행이 PENDING 으로 남는다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(IntegrationTestConfig.class)
@DisplayName("outbox 재발행 보장 = at-least-once")
class OutboxAtLeastOnceIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @MockitoSpyBean OutboxEventRepository outboxEventRepository;
    @Autowired OutboxPollingService pollingService;
    @Autowired OutboxEventJpaRepository outboxEventJpaRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final String TOPIC = "order.created";

    @BeforeEach
    void setUp() {
        cleanDatabase();
        outboxEventJpaRepository.deleteAll();
    }

    @Test
    @DisplayName("ack 후 상태 저장이 전부 실패하면 같은 이벤트가 두 번 발행되고, 소비 효과는 eventId 로 1회다")
    void publicationIsAtLeastOnceAndConsumptionIsIdempotent() {
        String eventId = UUID.randomUUID().toString();
        insertPending(eventId);

        // 사이클의 두 save(성공 경로 + 실패 처리 경로)를 모두 죽인다 — 프로세스가 ack 와 커밋 사이에서
        // 죽은 상황과 DB 에 남는 결과가 같다.
        doThrow(new RuntimeException("DB down")).when(outboxEventRepository).save(any());
        // 실패 처리 경로의 save(OutboxPollingService:122)는 try 밖이라 예외가 사이클을 뚫고 나온다 —
        // 프로세스가 ack 와 커밋 사이에서 죽는 것과 같은 상황이다. 삼키지 말고 그 사실을 고정한다.
        assertThatThrownBy(() -> pollingService.pollAndPublish())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB down");

        // 행은 PENDING 으로 남는다. **DB 를 다시 읽어 확인한다** — 인메모리 엔티티는 이미 PUBLISHED 다.
        assertThat(statusInDb(eventId)).isEqualTo("PENDING");

        // 장애 복구 후 다음 사이클.
        doCallRealMethod().when(outboxEventRepository).save(any());
        pollingService.pollAndPublish();
        assertThat(statusInDb(eventId)).isEqualTo("PUBLISHED");

        // broker 에는 같은 이벤트가 **2개 이상** 있다. 이것이 at-least-once 다.
        //
        // **정확히 2개를 단언하지 않는다.** 배경 poller(@Scheduled)도 같은 행을 집어가므로 중복 수는
        // 타이밍에 따라 달라진다 — 실측에서 3개가 나왔다. 그리고 애초에 ADR-0020 D1 은 중복 수에
        // 상한을 두지 않는다. 정확한 수를 단언하면 계약이 말하지 않는 것을 테스트가 주장하게 되고,
        // 스케줄러 타이밍에 흔들리는 flaky 가 된다.
        List<ConsumerRecord<String, String>> records = drain(TOPIC, 2);
        assertThat(records).hasSizeGreaterThanOrEqualTo(2);
        assertThat(records).extracting(ConsumerRecord::offset).doesNotHaveDuplicates();
        // 전부 같은 payload 다 — 소비자의 processed_events (event_id, consumer_group) UNIQUE 가 효과를
        // 1회로 접는다. 그 멱등이 성립하려면 eventId 가 재발행에도 보존돼야 한다.
        assertThat(records).extracting(ConsumerRecord::value).containsOnly(records.get(0).value());
        assertThat(records.get(0).value()).contains(eventId);
    }

    private void insertPending(String eventId) {
        jdbcTemplate.update("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, event_id, payload,
                                           status, retry_count, created_at, record_kind)
                VALUES ('ORDER', 'order-1', ?, ?, ?, 'PENDING', 0, NOW(6), 'DOMAIN')
                """, TOPIC, eventId, "{\"eventId\":\"" + eventId + "\"}");
    }

    private String statusInDb(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId);
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
            List<ConsumerRecord<String, String>> collected = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 20_000;
            while (collected.size() < expected && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.records(topic).forEach(collected::add);
            }
            return collected;
        }
    }
}

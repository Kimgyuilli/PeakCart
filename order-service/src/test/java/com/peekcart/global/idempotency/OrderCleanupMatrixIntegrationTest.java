package com.peekcart.global.idempotency;

import com.peekcart.global.outbox.OutboxEventCleanupScheduler;
import com.peekcart.support.IntegrationTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서비스×잡 매트릭스 검증 — order (ADR-0012 D5 · 구현 ② PR3).
 * order 는 발행 서비스 → processed + outbox cleanup bean 둘 다 소유.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(IntegrationTestConfig.class)
@DisplayName("서비스×잡 매트릭스 — order (processed + outbox)")
class OrderCleanupMatrixIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired ApplicationContext ctx;

    @Test
    @DisplayName("order 는 processed + outbox cleanup bean 둘 다 소유")
    void matrix_orderOwnsBothCleanupJobs() {
        assertThat(ctx.getBeanNamesForType(ProcessedEventCleanupScheduler.class)).hasSize(1);
        assertThat(ctx.getBeanNamesForType(OutboxEventCleanupScheduler.class)).hasSize(1);
    }
}

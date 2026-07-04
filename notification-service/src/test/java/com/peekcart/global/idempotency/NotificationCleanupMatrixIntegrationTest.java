package com.peekcart.global.idempotency;

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
 * 서비스×잡 매트릭스 검증 — notification (ADR-0012 D5 · 구현 ② PR3).
 * <p>notification 은 소비 전용(outbox 미소유)이므로 <b>processed cleanup 만</b> bean 으로 뜨고,
 * outbox cleanup 은 뜨지 않아야 한다. (user 서비스의 "잡 0" 은 클래스 물리 부재로 구조적으로 보장.)
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(IntegrationTestConfig.class)
@DisplayName("서비스×잡 매트릭스 — notification (processed cleanup only)")
class NotificationCleanupMatrixIntegrationTest {

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
    @DisplayName("processed cleanup bean 은 존재, outbox cleanup bean 은 부재")
    void matrix_notificationHasProcessedOnly() {
        assertThat(ctx.getBeanNamesForType(ProcessedEventCleanupScheduler.class)).hasSize(1);
        // outbox cleanup 은 notification 모듈에 클래스 자체가 없다 → bean 부재.
        assertThat(ctx.containsBean("outboxEventCleanupScheduler")).isFalse();
    }
}

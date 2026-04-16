package com.peekcart.global.observability;

import com.peekcart.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Testcontainers
@DisplayName("관측성 계약 회귀 테스트 (D-001/D-005 재발 방지)")
class ObservabilityMetricsIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    @DisplayName("비즈니스 엔드포인트 호출 후 /actuator/prometheus에 histogram bucket + application 태그가 노출된다")
    void prometheusEndpoint_exposesHistogramBucketAndApplicationTag() {
        ResponseEntity<String> businessResponse = restTemplate.getForEntity("/api/v1/products", String.class);
        assertThat(businessResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> prometheusResponse = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = prometheusResponse.getBody();
        assertThat(body).isNotNull();

        assertThat(body)
                .as("common tag application=peekcart 이 모든 메트릭에 부여되어야 한다 (P0-B: base management.metrics.tags.application)")
                .contains("application=\"peekcart\"");

        assertThat(body)
                .as("비즈니스 URI 에 대한 http_server_requests histogram bucket 이 노출되어야 한다 (D-001: MetricsConfig MeterFilter)")
                .containsPattern("http_server_requests_seconds_bucket\\{[^}]*uri=\"/api/v1/products\"[^}]*le=\"[^\"]+\"[^}]*\\}");
    }
}

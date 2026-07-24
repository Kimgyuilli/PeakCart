package com.peekcart.notification.infrastructure.security;

import com.peekcart.global.security.HeaderAuthenticationFilter;
import com.peekcart.support.IntegrationTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notification 서비스 보안 통합 회귀 — header-trust 전환(ADR-0013 D3 · PR3c).
 * Gateway 신뢰 헤더로 인증하며 서명 검증·blacklist 는 하지 않는다(Gateway 로 이관). 3-state 계약과
 * 공통 정책(401·permitAll·actuator)을 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(IntegrationTestConfig.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@DisplayName("Notification 보안 통합 테스트 (header-trust)")
class NotificationSecurityIntegrationTest {

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

    @Autowired TestRestTemplate restTemplate;
    @Autowired Map<String, SecurityFilterChain> securityFilterChains;

    @Test
    @DisplayName("신뢰 헤더 부재 요청은 401 로 거부된다 (3-state: 부재→보호경로 401)")
    void noHeaders_rejected() {
        ResponseEntity<String> res = restTemplate.getForEntity("/api/v1/notifications", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("정상 X-User-* 헤더는 인증되어 200 (3-state: 정상→인증)")
    void validHeaders_authenticated() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.GET, trusted(1L, "USER", "fam-1"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("X-User-Id 만 있고 Role 누락이면 401 (3-state: 부분 존재)")
    void partialHeaders_rejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, "1");
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("허용되지 않은 Role 은 401 (3-state: 미허용 role)")
    void invalidRole_rejected() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.GET, trusted(1L, "SUPERUSER", null), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("SecurityFilterChain 은 정확히 1개, HeaderAuthenticationFilter 는 1회만 등록된다 (configurer 동등성)")
    void singleChain_singleHeaderFilter() {
        assertThat(securityFilterChains).hasSize(1);
        SecurityFilterChain chain = securityFilterChains.values().iterator().next();
        long headerFilterCount = chain.getFilters().stream()
                .filter(f -> f instanceof HeaderAuthenticationFilter).count();
        assertThat(headerFilterCount).isEqualTo(1);
    }

    @Test
    @DisplayName("/actuator/health 는 비인증 permitAll 200 (게이트 c · ADR-0009 S4)")
    void actuatorHealth_permitAll() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private HttpEntity<Void> trusted(Object userId, String role, String familyId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, String.valueOf(userId));
        headers.set(HeaderAuthenticationFilter.USER_ROLE_HEADER, role);
        if (familyId != null) {
            headers.set(HeaderAuthenticationFilter.USER_FAMILY_ID_HEADER, familyId);
        }
        return new HttpEntity<>(headers);
    }
}

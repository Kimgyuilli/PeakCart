package com.peekcart.user.infrastructure.security;

import com.peekcart.global.filter.MdcFilter;
import com.peekcart.global.security.HeaderAuthenticationFilter;
import com.peekcart.support.TestRsaKeys;
import com.peekcart.user.domain.model.User;
import com.peekcart.user.domain.repository.UserRepository;
import com.peekcart.user.presentation.dto.request.SignupRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-service 보안 통합 회귀 — header-trust 전환(ADR-0013 D3 · PR3c).
 * Gateway 가 검증 후 주입한 {@code X-User-*} 신뢰 헤더로 인증하며, 서비스는 서명 검증·blacklist 를 하지 않는다
 * (그 책임은 Gateway 로 이관 — gateway 모듈 테스트가 소유). 3-state 계약과 공통 정책(401·permitAll·actuator)을 고정한다.
 * <ul>
 *   <li>3-state: 헤더 부재→401(보호) / 정상 헤더→인증 / 형식오류(부분·비숫자·임의 role·blank·중복)→401</li>
 *   <li>configurer 동등성: SecurityFilterChain 1개 · HeaderAuthenticationFilter 1회 · actuator permitAll(S4)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@DisplayName("user-service 보안 통합 테스트 (header-trust)")
class UserSecurityIntegrationTest {

    /**
     * user-service 는 여전히 토큰을 서명한다(JwtTokenSigner·JwkController) — RS256 키가 없으면 컨텍스트가
     * 부팅하지 못한다. header-trust 인증은 이 키를 쓰지 않지만, 서명 빈 생성을 위해 키쌍을 주입한다
     * (개인키 커밋 금지, ADR-0013 D2 — 런타임 생성).
     */
    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        TestRsaKeys.register(registry);
    }

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");

    // user-service 는 blacklist/deny write(AuthService)·adapter 빈을 보유하므로 컨텍스트 부팅에 Redis 가 필요하다.
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @Autowired TestRestTemplate restTemplate;
    @Autowired UserRepository userRepository;
    @Autowired Map<String, SecurityFilterChain> securityFilterChains;

    private Long userId;

    @BeforeEach
    void setUp() {
        // getMe 가 실제 조회하므로 회원 행이 필요. RANDOM_PORT @SpringBootTest 는 롤백되지 않으므로 고유 이메일.
        User user = userRepository.save(
                User.create("sec-" + System.nanoTime() + "@peekcart.com", "hashed-pw", "보안테스트"));
        userId = user.getId();
    }

    @Test
    @DisplayName("신뢰 헤더 부재 요청은 401 로 거부된다 (3-state: 부재→보호경로 401)")
    void noHeaders_rejected() {
        ResponseEntity<String> res = restTemplate.getForEntity("/api/v1/users/me", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("공개 endpoint /api/v1/auth/signup 은 헤더 없이도 허용된다 (permitAll)")
    void publicAuthEndpoint_permitAll() {
        SignupRequest body = new SignupRequest(
                "signup-" + System.nanoTime() + "@peekcart.com", "password123", "공개테스트");
        ResponseEntity<String> res = restTemplate.postForEntity("/api/v1/auth/signup", body, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("정상 X-User-* 헤더는 인증되어 200 (3-state: 정상→인증)")
    void validHeaders_authenticated() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, trusted(userId, "USER", "fam-1"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("family-less(전환기 레거시) 헤더도 인증된다 — X-User-Family-Id 없이 200")
    void familyLessHeaders_authenticated() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, String.valueOf(userId));
        headers.set(HeaderAuthenticationFilter.USER_ROLE_HEADER, "USER");
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("X-User-Id 만 있고 Role 누락이면 401 (3-state: 부분 존재)")
    void partialHeaders_rejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, String.valueOf(userId));
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("비숫자 X-User-Id 는 401 (3-state: 형식 오류)")
    void nonNumericId_rejected() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, trusted("not-a-number", "USER"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("허용되지 않은 Role 은 401 (3-state: 미허용 role)")
    void invalidRole_rejected() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, trusted(String.valueOf(userId), "SUPERUSER"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("SecurityFilterChain 은 1개, HeaderAuthenticationFilter·MdcFilter 각 1회 등록 (configurer 동등성)")
    void singleChain_headerAndMdcFilters() {
        assertThat(securityFilterChains).hasSize(1);
        SecurityFilterChain chain = securityFilterChains.values().iterator().next();
        long headerFilterCount = chain.getFilters().stream()
                .filter(f -> f instanceof HeaderAuthenticationFilter).count();
        // MdcFilter 보존은 configurer 동등성의 일부 — 누락 시 traceId/userId MDC 가 조용히 사라진다(review #6).
        long mdcFilterCount = chain.getFilters().stream()
                .filter(f -> f instanceof MdcFilter).count();
        assertThat(headerFilterCount).isEqualTo(1);
        assertThat(mdcFilterCount).isEqualTo(1);
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
        headers.set(HeaderAuthenticationFilter.USER_FAMILY_ID_HEADER, familyId);
        return new HttpEntity<>(headers);
    }

    private HttpEntity<Void> trusted(Object userId, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HeaderAuthenticationFilter.USER_ID_HEADER, String.valueOf(userId));
        headers.set(HeaderAuthenticationFilter.USER_ROLE_HEADER, role);
        return new HttpEntity<>(headers);
    }
}

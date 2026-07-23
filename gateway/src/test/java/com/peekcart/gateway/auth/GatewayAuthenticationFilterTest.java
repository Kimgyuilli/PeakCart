package com.peekcart.gateway.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import com.peekcart.gateway.ratelimit.RateLimiterUnavailableException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gateway 인증 필터 계약 회귀 (계획 P12/P19).
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li>외부 유입 {@code X-User-*} 는 <b>공개 경로 포함 항상</b> 제거(spoof 차단)</li>
 *   <li>검증 성공 시 신뢰 헤더 재주입, family-less 는 헤더 미주입("null" 문자열 금지)</li>
 *   <li>보호 경로 무토큰/무효토큰 → 401 · 공개 경로 → 익명 통과</li>
 *   <li>의존성 장애(Redis/JWKS) → <b>503</b>, 공개 경로여도 통과시키지 않음(fail-closed)</li>
 *   <li>PR3a: {@code Authorization} 은 <b>그대로 전달</b>(구버전 서비스 병행 동작)</li>
 * </ul>
 */
@DisplayName("GatewayAuthenticationFilter — strip/inject · 401/503 · 공개 경로")
class GatewayAuthenticationFilterTest {

    private static final String TOKEN = "valid.jwt.token";

    private GatewayJwtVerifier verifier;
    private TokenDenyLookup denyLookup;
    private GatewayAuthenticationFilter filter;
    private AtomicReference<ServerWebExchange> forwarded;

    @BeforeEach
    void setUp() {
        verifier = mock(GatewayJwtVerifier.class);
        denyLookup = mock(TokenDenyLookup.class);
        PublicEndpointProperties publicProps = new PublicEndpointProperties(List.of(
                "POST /api/v1/auth/login",
                "GET /api/v1/products",
                "GET /api/v1/products/**",
                "POST /api/v1/payments/webhook"));
        filter = new GatewayAuthenticationFilter(verifier, denyLookup, publicProps);
        forwarded = new AtomicReference<>();
    }

    private GatewayFilterChain chain() {
        return exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        };
    }

    private ServerHttpRequest forwardedRequest() {
        assertThat(forwarded.get()).as("체인으로 전달되지 않음").isNotNull();
        return forwarded.get().getRequest();
    }

    private void stubValid(String familyId) {
        when(verifier.verify(anyString()))
                .thenReturn(Mono.just(new GatewayClaims(42L, "USER", familyId, Instant.now().plusSeconds(300))));
        when(denyLookup.isDenied(anyString(), any())).thenReturn(Mono.just(false));
    }

    @Nested
    @DisplayName("헤더 strip / inject")
    class Headers {

        @Test
        @DisplayName("검증 성공 → 외부 X-User-* 제거 후 검증값으로 재주입 (spoof 무력화)")
        void validToken_stripsSpoofAndInjects() {
            stubValid("fam-9");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                            .header("X-User-Id", "999")            // 외부 위조 시도
                            .header("X-User-Role", "ADMIN")        // 권한 상승 시도
                            .header("X-User-Family-Id", "evil"));

            filter.filter(exchange, chain()).block();

            HttpHeaders headers = forwardedRequest().getHeaders();
            assertThat(headers.getFirst("X-User-Id")).isEqualTo("42");
            assertThat(headers.getFirst("X-User-Role")).isEqualTo("USER");
            assertThat(headers.getFirst("X-User-Family-Id")).isEqualTo("fam-9");
        }

        @Test
        @DisplayName("family-less 레거시 토큰 → X-User-Family-Id 미주입 ('null' 문자열 금지)")
        void familyLessToken_doesNotInjectFamilyHeader() {
            stubValid(null);
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                            .header("X-User-Family-Id", "evil"));

            filter.filter(exchange, chain()).block();

            HttpHeaders headers = forwardedRequest().getHeaders();
            assertThat(headers.getFirst("X-User-Id")).isEqualTo("42");
            assertThat(headers.containsKey("X-User-Family-Id")).isFalse();
        }

        @Test
        @DisplayName("공개 경로 + 무토큰 → 외부 X-User-* 는 여전히 제거된다")
        void publicPath_stillStripsSpoofedHeaders() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products/1")
                            .header("X-User-Id", "999")
                            .header("X-User-Role", "ADMIN"));

            filter.filter(exchange, chain()).block();

            HttpHeaders headers = forwardedRequest().getHeaders();
            assertThat(headers.containsKey("X-User-Id")).isFalse();
            assertThat(headers.containsKey("X-User-Role")).isFalse();
        }

        @Test
        @DisplayName("PR3a 전환기: Authorization 은 그대로 전달된다 (구버전 서비스 병행)")
        void pr3a_forwardsAuthorizationHeader() {
            stubValid("fam-1");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(forwardedRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .isEqualTo("Bearer " + TOKEN);
        }
    }

    @Nested
    @DisplayName("보호 경로")
    class Protected {

        @Test
        @DisplayName("무토큰 → 401")
        void noToken_401() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders"));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(forwarded.get()).as("거부된 요청이 업스트림으로 새면 안 된다").isNull();
        }

        @Test
        @DisplayName("무효 토큰 → 401")
        void invalidToken_401() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.error(new GatewayJwtVerifier.InvalidTokenException("bad")));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(forwarded.get()).isNull();
        }

        @Test
        @DisplayName("deny hit(blacklist/family) → 401")
        void deniedToken_401() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.just(new GatewayClaims(42L, "USER", "fam-1", Instant.now().plusSeconds(300))));
            when(denyLookup.isDenied(anyString(), any())).thenReturn(Mono.just(true));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(forwarded.get()).isNull();
        }

        @Test
        @DisplayName("Bearer 아닌 Authorization → 무토큰 취급 401")
        void nonBearerAuthorization_401() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("실행 순서 · 다운스트림 오류 분리")
    class OrderingAndDownstream {

        @Test
        @DisplayName("라우트 필터(order 1..n)보다 먼저 실행돼야 RateLimiter 가 검증된 값을 쓴다 (GW-2 c2:1)")
        void order_isBeforeRouteFilters() {
            assertThat(filter.getOrder())
                    .as("route 정의 필터는 order 1 부터 부여된다 — 그보다 작아야 strip/검증이 먼저 일어난다")
                    .isLessThan(1)
                    .isEqualTo(GatewayAuthenticationFilter.AUTH_FILTER_ORDER);
        }

        @Test
        @DisplayName("검증 성공 시 userId 를 exchange attribute 로 노출한다 (RateLimiter 가 헤더 대신 이걸 신뢰)")
        void exposesAuthenticatedUserIdAttribute() {
            stubValid("fam-1");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                            .header("X-User-Id", "999"));

            filter.filter(exchange, chain()).block();

            assertThat(forwarded.get().<String>getAttribute(
                    GatewayAuthenticationFilter.AUTHENTICATED_USER_ID_ATTR))
                    .as("위조 헤더(999)가 아니라 검증된 42 여야 한다")
                    .isEqualTo("42");
        }

        @Test
        @DisplayName("다운스트림 오류는 401 로 오분류되지 않고 전파된다 (GW-2 c2:2)")
        void downstreamError_isNotConvertedTo401() {
            stubValid("fam-1");
            GatewayFilterChain failing = exchange -> Mono.error(new IllegalStateException("upstream down"));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            StepVerifier.create(filter.filter(exchange, failing))
                    .expectError(IllegalStateException.class)
                    .verify();

            assertThat(exchange.getResponse().getStatusCode())
                    .as("업스트림 장애를 401 로 감추면 안 된다")
                    .isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("공개 경로 무토큰 요청에서 체인은 정확히 한 번만 호출된다 (GW-2 c2:2 이중 호출)")
        void publicPath_chainInvokedExactlyOnce() {
            AtomicInteger calls = new AtomicInteger();
            GatewayFilterChain counting = exchange -> {
                calls.incrementAndGet();
                forwarded.set(exchange);
                return Mono.empty();
            };
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products"));

            filter.filter(exchange, counting).block();

            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("RateLimiter 백엔드 장애 → 503 (429 아님)")
        void rateLimiterUnavailable_is503() {
            stubValid("fam-1");
            GatewayFilterChain failing = exchange -> Mono.error(
                    new RateLimiterUnavailableException("redis down", null));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, failing).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("의존성 장애 = 503 (401 로 강등하지 않음)")
    class Dependency {

        @Test
        @DisplayName("Redis 조회 실패 → 503")
        void redisDown_503() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.just(new GatewayClaims(42L, "USER", "fam-1", Instant.now().plusSeconds(300))));
            when(denyLookup.isDenied(anyString(), any()))
                    .thenReturn(Mono.error(new TokenDenyLookup.DenyLookupUnavailableException("down", null)));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(forwarded.get()).isNull();
        }

        @Test
        @DisplayName("JWKS 장애 → 503")
        void jwksDown_503() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.error(new JwksKeyRegistry.JwksUnavailableException("down", null)));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("공개 경로여도 의존성 장애는 익명 통과로 감추지 않는다 → 503")
        void publicPath_dependencyFailure_stillFailsClosed() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.just(new GatewayClaims(42L, "USER", "fam-1", Instant.now().plusSeconds(300))));
            when(denyLookup.isDenied(anyString(), any()))
                    .thenReturn(Mono.error(new TokenDenyLookup.DenyLookupUnavailableException("down", null)));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products/1")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(forwarded.get()).isNull();
        }
    }

    @Nested
    @DisplayName("공개 경로")
    class Public {

        @Test
        @DisplayName("무토큰 공개 GET → 통과")
        void publicGet_noToken_passes() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products"));

            filter.filter(exchange, chain()).block();

            assertThat(forwarded.get()).isNotNull();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }

        @Test
        @DisplayName("무토큰 공개 POST(login/webhook) → 통과")
        void publicPost_noToken_passes() {
            MockServerWebExchange login = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/auth/login"));
            filter.filter(login, chain()).block();
            assertThat(forwarded.get()).isNotNull();

            forwarded.set(null);
            MockServerWebExchange webhook = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/payments/webhook"));
            filter.filter(webhook, chain()).block();
            assertThat(forwarded.get()).isNotNull();
        }

        @Test
        @DisplayName("공개 경로 + 무효 토큰 → 401 (익명 강등 금지, GW-2 c2:4)")
        void publicPath_invalidToken_isRejected() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.error(new GatewayJwtVerifier.InvalidTokenException("bad")));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products/1")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                            .header("X-User-Id", "999"));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(forwarded.get()).isNull();
        }

        @Test
        @DisplayName("공개 경로 + deny hit 토큰 → 401 (로그아웃/reuse 무효화가 공개 경로로 우회되면 안 된다)")
        void publicPath_deniedToken_isRejected() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.just(new GatewayClaims(42L, "USER", "fam-1", Instant.now().plusSeconds(300))));
            when(denyLookup.isDenied(anyString(), any())).thenReturn(Mono.just(true));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products/1")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(forwarded.get()).isNull();
        }

        @Test
        @DisplayName("공개 경로 + 유효 토큰 → 통과하며 신뢰 헤더도 주입된다")
        void publicPath_validToken_injectsHeaders() {
            stubValid("fam-3");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products/1")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(forwarded.get()).isNotNull();
            assertThat(forwardedRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("42");
        }

        @Test
        @DisplayName("method 로 좁힌다: POST /api/v1/products 는 공개가 아니다 → 401")
        void productsPost_isNotPublic() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/products"));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("admin 경로는 공개가 아니다 → 401")
        void adminProducts_isNotPublic() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/admin/products"));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}

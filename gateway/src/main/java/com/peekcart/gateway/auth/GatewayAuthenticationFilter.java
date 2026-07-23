package com.peekcart.gateway.auth;

import com.peekcart.gateway.ratelimit.RateLimiterUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Gateway 인증 필터 (ADR-0013 D3 · 구현 ③ PR3a) — 검증 3단계.
 *
 * <ol>
 *   <li><b>헤더 strip</b>: 외부에서 유입된 {@code X-User-*} 를 <b>항상</b> 제거한다(공개 경로 포함).</li>
 *   <li><b>서명/만료 검증</b>(RS256 via JWKS, 전환기 HS512) → <b>blacklist/family deny</b>(Redis)</li>
 *   <li><b>신뢰 헤더 주입</b> + 검증된 userId 를 exchange attribute 로 노출(RateLimiter 용)</li>
 * </ol>
 *
 * <p><b>실행 순서</b>(GW-2 c2:1/c3:2): 반드시 라우트 필터(`RequestRateLimiter`)보다 <b>먼저</b> 실행돼야
 * 한다. 라우트 정의 필터는 order 1..n 을 받으므로 본 필터는 그보다 앞선 {@link #AUTH_FILTER_ORDER} 를
 * 쓴다. 뒤에 실행되면 RateLimiter 가 <b>검증 전 외부 {@code X-User-Id}</b> 를 키로 삼아, 헤더만 바꿔
 * 사용자별 제한을 무한 회피할 수 있다.
 *
 * <p><b>응답 계약</b>(계획 P12 행렬): 서명오류·만료·exp 부재·unknown kid·deny hit → <b>401</b> /
 * JWKS·Redis 의존성 장애 → <b>503</b> / rate limit 초과 → 429(RateLimiter 소관).
 *
 * <p><b>PR3a 전환기</b>: {@code Authorization} 을 제거하지 않고 그대로 전달한다(구버전 서비스가 아직
 * Bearer 를 검증 — 계획 P18 ①). PR3d 에서 전달을 중단한다.
 */
@Component
public class GatewayAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthenticationFilter.class);

    /**
     * 라우트 정의 필터(order 1..n)보다 앞서고, 응답 기록 필터(NettyWriteResponseFilter, order -1)보다도
     * 앞서 감싸도록 음수 order 를 쓴다.
     */
    public static final int AUTH_FILTER_ORDER = -100;

    /** 검증이 끝난 userId — RateLimiter 가 <b>이 값만</b> 신뢰한다(요청 헤더 직접 신뢰 금지). */
    public static final String AUTHENTICATED_USER_ID_ATTR = "peekcart.gateway.authenticatedUserId";

    static final List<String> TRUSTED_HEADERS =
            List.of("X-User-Id", "X-User-Role", "X-User-Family-Id");

    private static final String BEARER_PREFIX = "Bearer ";

    private final GatewayJwtVerifier verifier;
    private final TokenDenyLookup denyLookup;
    private final List<PublicEndpointProperties.Rule> publicRules;

    public GatewayAuthenticationFilter(GatewayJwtVerifier verifier,
                                       TokenDenyLookup denyLookup,
                                       PublicEndpointProperties publicEndpointProperties) {
        this.verifier = verifier;
        this.denyLookup = denyLookup;
        this.publicRules = publicEndpointProperties.rules();
    }

    @Override
    public int getOrder() {
        return AUTH_FILTER_ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        boolean isPublic = isPublicEndpoint(request);
        String token = resolveToken(request);

        if (token == null) {
            // 토큰 미제시: 공개 경로는 익명 통과, 보호 경로는 401.
            return isPublic
                    ? proceed(chain, stripTrusted(exchange))
                    : reject(exchange, HttpStatus.UNAUTHORIZED, "missing_token");
        }

        // 토큰이 제시됐다면 공개 경로여도 반드시 검증한다(GW-2 c2:4): 만료·위조·deny 토큰을 익명으로
        // 강등해 통과시키면 로그아웃/reuse 무효화가 공개 경로에서 우회된다.
        return authenticate(exchange, token)
                // 인증 단계 오류만 여기서 처리한다. chain.filter 는 아래에서 별도로 호출하므로
                // 업스트림/라우팅 오류가 401 로 오분류되지 않는다(GW-2 c2:2).
                .flatMap(authenticated -> proceed(chain, authenticated))
                .onErrorResume(e -> isAuthFailure(e)
                        ? rejectByCause(exchange, e)
                        : Mono.error(e));
    }

    /** 검증 성공 시 신뢰 헤더가 주입된 exchange 를 반환한다. 실패는 error signal 로만 표현한다. */
    private Mono<ServerWebExchange> authenticate(ServerWebExchange exchange, String token) {
        return verifier.verify(token)
                .flatMap(claims -> denyLookup.isDenied(token, claims.familyId())
                        .flatMap(denied -> denied
                                ? Mono.<GatewayClaims>error(new GatewayJwtVerifier.InvalidTokenException(
                                        "차단된 토큰(blacklist/family deny)"))
                                : Mono.just(claims)))
                .map(claims -> withTrustedHeaders(exchange, claims));
    }

    /**
     * 다운스트림 진행. rate limiter 의 의존성 장애만 503 으로 변환하고, 그 외 업스트림 오류는
     * 기본 오류 처리에 맡긴다(401 오분류 금지).
     */
    private Mono<Void> proceed(GatewayFilterChain chain, ServerWebExchange exchange) {
        return chain.filter(exchange)
                .onErrorResume(RateLimiterUnavailableException.class,
                        e -> reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "rate_limiter_unavailable"));
    }

    private static boolean isAuthFailure(Throwable e) {
        return e instanceof GatewayJwtVerifier.InvalidTokenException
                || e instanceof JwksKeyRegistry.JwksUnavailableException
                || e instanceof TokenDenyLookup.DenyLookupUnavailableException;
    }

    private Mono<Void> rejectByCause(ServerWebExchange exchange, Throwable e) {
        // 의존성 장애(503)와 보안 판정 실패(401)를 분리한다 — 장애를 401 로 감추면
        // 클라이언트가 재인증을 시도하고 실제 원인이 가려진다.
        if (e instanceof TokenDenyLookup.DenyLookupUnavailableException
                || e instanceof JwksKeyRegistry.JwksUnavailableException) {
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable");
        }
        return reject(exchange, HttpStatus.UNAUTHORIZED, "invalid_token");
    }

    private boolean isPublicEndpoint(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        PathContainer path = request.getPath().pathWithinApplication();
        return publicRules.stream().anyMatch(rule -> rule.matches(method, path));
    }

    private static String resolveToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String value = header.substring(BEARER_PREFIX.length()).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    /** 외부 유입 신뢰 헤더 제거만 수행(주입 없음). */
    private static ServerWebExchange stripTrusted(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(r -> r.headers(headers -> TRUSTED_HEADERS.forEach(headers::remove)))
                .build();
    }

    /** 외부 헤더 제거 후 검증된 값으로 재주입 + RateLimiter 용 attribute 기록. */
    private static ServerWebExchange withTrustedHeaders(ServerWebExchange exchange, GatewayClaims claims) {
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(headers -> {
                    TRUSTED_HEADERS.forEach(headers::remove);
                    headers.set("X-User-Id", String.valueOf(claims.userId()));
                    if (claims.role() != null) {
                        headers.set("X-User-Role", claims.role());
                    }
                    // family-less 레거시 토큰(전환기)은 헤더를 넣지 않는다 — "null" 문자열 주입 금지
                    if (claims.familyId() != null && !claims.familyId().isBlank()) {
                        headers.set("X-User-Family-Id", claims.familyId());
                    }
                }))
                .build();
        mutated.getAttributes().put(AUTHENTICATED_USER_ID_ATTR, String.valueOf(claims.userId()));
        return mutated;
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String reason) {
        log.debug("gateway 거부: status={} reason={} path={}",
                status.value(), reason, exchange.getRequest().getPath());
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().add("X-Auth-Failure-Reason", reason);
        return exchange.getResponse().setComplete();
    }
}

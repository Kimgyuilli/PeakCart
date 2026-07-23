package com.peekcart.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;

/**
 * Gateway 전용 JWT 검증기 (reactive) — ADR-0013 D1/D3 · 구현 ③ PR3a.
 *
 * <p>servlet 측 {@code JwtTokenVerifier} 와 <b>동일 계약</b>을 재구현한다(B6: common-auth 는 servlet MVC
 * 스택이라 재사용 불가). 동등성은 conformance golden vector 로 고정한다(계획 P19 · loop2 #6).
 *
 * <p><b>alg allow-list</b>: RS256(JWKS kid 선택) + 전환기 <b>HS512</b> fallback(기본 off).
 * HS256/HS384/none 은 상시 거부 — 레거시 토큰의 실제 alg 는 HS512 다(HS256 아님).
 *
 * <p>키 해석이 비동기(JWKS fetch)라 jjwt {@code keyLocator}(동기) 대신
 * <b>헤더 선파싱 → 키 해석(Mono) → 서명 검증</b> 순서로 처리한다. 선파싱한 헤더는 키 <i>선택</i>에만
 * 쓰이며, 신뢰는 이후 서명 검증이 부여한다.
 */
@Component
public class GatewayJwtVerifier {

    private final JwksKeyRegistry keyRegistry;
    private final JwtGatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final SecretKey hs512Key;

    public GatewayJwtVerifier(JwksKeyRegistry keyRegistry,
                              JwtGatewayProperties properties,
                              ObjectMapper objectMapper) {
        this.keyRegistry = keyRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.hs512Key = (properties.hs512FallbackEnabled() && properties.hs512Secret() != null)
                ? Keys.hmacShaKeyFor(properties.hs512Secret().getBytes(StandardCharsets.UTF_8))
                : null;
    }

    /**
     * 토큰을 검증하고 클레임을 반환한다.
     *
     * @return 실패 시 {@link InvalidTokenException}(→401) 또는
     *         {@link JwksKeyRegistry.JwksUnavailableException}(→503) 로 종료되는 Mono
     */
    public Mono<GatewayClaims> verify(String token) {
        final JwsHeader header;
        try {
            header = readHeader(token);
        } catch (RuntimeException e) {
            return Mono.error(new InvalidTokenException("JWT 헤더 파싱 실패", e));
        }
        return resolveKey(header).flatMap(key -> parseClaims(token, key));
    }

    private Mono<Key> resolveKey(JwsHeader header) {
        if ("RS256".equals(header.alg())) {
            return keyRegistry.resolve(header.kid())
                    .map(Key.class::cast)
                    // unknown kid = 위조/폐기 → 401. JwksUnavailableException 은 전파해 503.
                    .onErrorMap(JwksKeyRegistry.UnknownKidException.class,
                            e -> new InvalidTokenException(e.getMessage(), e));
        }
        // 전환기 대칭키 fallback — 레거시는 512bit secret 이라 정확히 HS512 만 수용(PR4 P22 에서 제거)
        if ("HS512".equals(header.alg()) && hs512Key != null) {
            return Mono.just(hs512Key);
        }
        return Mono.error(new InvalidTokenException("허용되지 않은 서명 알고리즘: " + header.alg()));
    }

    private Mono<GatewayClaims> parseClaims(String token, Key key) {
        try {
            var parser = Jwts.parser();
            if (key instanceof SecretKey secretKey) {
                parser.verifyWith(secretKey);
            } else {
                parser.verifyWith((PublicKey) key);
            }
            Claims claims = parser.build().parseSignedClaims(token).getPayload();
            // exp 필수(GW-2 c1:1): jjwt 는 exp 가 *있을 때만* 만료를 검사한다. null 을 허용하면
            // 서명만 유효한 무기한 토큰이 통과해 만료·로그아웃·회전이 무력화된다.
            if (claims.getExpiration() == null) {
                return Mono.error(new InvalidTokenException("exp 클레임 부재 — 무기한 토큰 거부"));
            }
            return Mono.just(new GatewayClaims(
                    Long.parseLong(claims.getSubject()),
                    claims.get("role", String.class),
                    claims.get("family_id", String.class),
                    claims.getExpiration().toInstant()
            ));
        } catch (RuntimeException e) {
            // 서명 불일치·만료·subject 형식 오류 등 → 401
            return Mono.error(new InvalidTokenException("JWT 검증 실패", e));
        }
    }

    private JwsHeader readHeader(String token) {
        int dot = token.indexOf('.');
        if (dot <= 0) {
            throw new IllegalArgumentException("JWT 형식 오류");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(token.substring(0, dot));
        try {
            JsonNode node = objectMapper.readTree(decoded);
            return new JwsHeader(text(node, "alg"), text(node, "kid"));
        } catch (Exception e) {
            throw new IllegalArgumentException("JWT 헤더 디코딩 실패", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    record JwsHeader(String alg, String kid) {
    }

    /** 서명/만료/alg/kid 문제 — 인증 실패로 401. */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }

        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

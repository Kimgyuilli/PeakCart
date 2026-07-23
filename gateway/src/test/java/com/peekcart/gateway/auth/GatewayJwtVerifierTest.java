package com.peekcart.gateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gateway 검증기 alg allow-list · kid · 응답 분류 회귀 (계획 P12/P19).
 *
 * <p>servlet {@code JwtTokenVerifierTest} 와 동일한 계약을 reactive 측에서 고정한다.
 * 키는 테스트 런타임 생성 — gateway 는 {@code :common} testFixtures 를 의존할 수 없다
 * (assertGatewayHasNoServletDeps 가 project 의존 자체를 금지).
 */
@DisplayName("GatewayJwtVerifier — alg allow-list / kid / 실패 분류")
class GatewayJwtVerifierTest {

    private static final String KID = "test-kid-1";
    private static final String HS_SECRET = "test-secret-that-is-long-enough-for-hs512-at-least-64-bytes-padding!!";

    private KeyPair keyPair;
    private JwksKeyRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        registry = mock(JwksKeyRegistry.class);
        when(registry.resolve(KID)).thenReturn(Mono.just((RSAPublicKey) keyPair.getPublic()));
    }

    private GatewayJwtVerifier verifier(boolean hs512Fallback) {
        JwtGatewayProperties props = new JwtGatewayProperties(
                "http://user/.well-known/jwks.json",
                Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMinutes(5),
                hs512Fallback, HS_SECRET);
        return new GatewayJwtVerifier(registry, props, new ObjectMapper());
    }

    private String rs256Token(String kid) {
        return Jwts.builder()
                .header().keyId(kid).and()
                .subject("42").claim("role", "USER").claim("family_id", "fam-1")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith((RSAPrivateKey) keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private String hmacToken(io.jsonwebtoken.security.MacAlgorithm alg) {
        SecretKey key = Keys.hmacShaKeyFor(HS_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("42").claim("role", "USER")
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(key, alg)
                .compact();
    }

    @Nested
    @DisplayName("RS256 (정본)")
    class Rs256 {

        @Test
        @DisplayName("RS256 왕복 — claims 매핑(userId/role/family_id)")
        void rs256_roundTrip() {
            StepVerifier.create(verifier(false).verify(rs256Token(KID)))
                    .assertNext(claims -> {
                        assertThat(claims.userId()).isEqualTo(42L);
                        assertThat(claims.role()).isEqualTo("USER");
                        assertThat(claims.familyId()).isEqualTo("fam-1");
                        assertThat(claims.expiration()).isNotNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("unknown kid → refresh 후에도 부재면 InvalidToken(401)")
        void unknownKid_isUnauthorized() {
            when(registry.resolve("other-kid"))
                    .thenReturn(Mono.error(new JwksKeyRegistry.UnknownKidException("알 수 없는 kid")));

            StepVerifier.create(verifier(false).verify(rs256Token("other-kid")))
                    .expectError(GatewayJwtVerifier.InvalidTokenException.class)
                    .verify();
        }

        @Test
        @DisplayName("JWKS 장애 → JwksUnavailable 전파(503) — 401 로 강등하지 않는다")
        void jwksDown_propagatesUnavailable() {
            when(registry.resolve(anyString()))
                    .thenReturn(Mono.error(new JwksKeyRegistry.JwksUnavailableException("down", null)));

            StepVerifier.create(verifier(false).verify(rs256Token(KID)))
                    .expectError(JwksKeyRegistry.JwksUnavailableException.class)
                    .verify();
        }

        @Test
        @DisplayName("다른 키로 서명된 토큰(위조) → 401")
        void forgedSignature_rejected() throws Exception {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair attacker = g.generateKeyPair();
            String forged = Jwts.builder()
                    .header().keyId(KID).and()
                    .subject("42").claim("role", "ADMIN")
                    .expiration(Date.from(Instant.now().plusSeconds(300)))
                    .signWith((RSAPrivateKey) attacker.getPrivate(), Jwts.SIG.RS256)
                    .compact();

            StepVerifier.create(verifier(false).verify(forged))
                    .expectError(GatewayJwtVerifier.InvalidTokenException.class)
                    .verify();
        }

        @Test
        @DisplayName("만료 토큰 → 401")
        void expired_rejected() {
            String expired = Jwts.builder()
                    .header().keyId(KID).and()
                    .subject("42").claim("role", "USER")
                    .expiration(Date.from(Instant.now().minusSeconds(10)))
                    .signWith((RSAPrivateKey) keyPair.getPrivate(), Jwts.SIG.RS256)
                    .compact();

            StepVerifier.create(verifier(false).verify(expired))
                    .expectError(GatewayJwtVerifier.InvalidTokenException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("전환기 HMAC fallback — HS512 정확 한정")
    class HmacFallback {

        @Test
        @DisplayName("fallback off(기본) → HS512 거부")
        void hs512_rejectedWhenDisabled() {
            StepVerifier.create(verifier(false).verify(hmacToken(Jwts.SIG.HS512)))
                    .expectError(GatewayJwtVerifier.InvalidTokenException.class)
                    .verify();
        }

        @Test
        @DisplayName("fallback on → HS512 수용 (레거시 토큰의 실제 alg)")
        void hs512_acceptedWhenEnabled() {
            StepVerifier.create(verifier(true).verify(hmacToken(Jwts.SIG.HS512)))
                    .assertNext(claims -> assertThat(claims.userId()).isEqualTo(42L))
                    .verifyComplete();
        }

        @Test
        @DisplayName("fallback on 이어도 HS256 은 상시 거부 (allow-list 과확장 방지)")
        void hs256_alwaysRejected() {
            StepVerifier.create(verifier(true).verify(hmacToken(Jwts.SIG.HS256)))
                    .expectError(GatewayJwtVerifier.InvalidTokenException.class)
                    .verify();
        }

        @Test
        @DisplayName("fallback on 이어도 HS384 는 상시 거부")
        void hs384_alwaysRejected() {
            StepVerifier.create(verifier(true).verify(hmacToken(Jwts.SIG.HS384)))
                    .expectError(GatewayJwtVerifier.InvalidTokenException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("형식/알고리즘 위조")
    class Malformed {

        @Test
        @DisplayName("alg=none → 거부")
        void algNone_rejected() {
            String unsigned = Jwts.builder()
                    .subject("42").claim("role", "ADMIN")
                    .expiration(Date.from(Instant.now().plusSeconds(300)))
                    .compact();

            StepVerifier.create(verifier(true).verify(unsigned))
                    .expectError(GatewayJwtVerifier.InvalidTokenException.class)
                    .verify();
        }

        @Test
        @DisplayName("JWT 형식이 아닌 문자열 → 거부(예외 누출 없음)")
        void garbage_rejected() {
            StepVerifier.create(verifier(true).verify("not-a-jwt"))
                    .expectError(GatewayJwtVerifier.InvalidTokenException.class)
                    .verify();
        }

        @Test
        @DisplayName("kid 없는 RS256 → 거부")
        void rs256WithoutKid_rejected() {
            when(registry.resolve(null))
                    .thenReturn(Mono.error(new JwksKeyRegistry.UnknownKidException("kid 부재")));
            String noKid = Jwts.builder()
                    .subject("42").claim("role", "USER")
                    .expiration(Date.from(Instant.now().plusSeconds(300)))
                    .signWith((RSAPrivateKey) keyPair.getPrivate(), Jwts.SIG.RS256)
                    .compact();

            StepVerifier.create(verifier(false).verify(noKid))
                    .expectError(GatewayJwtVerifier.InvalidTokenException.class)
                    .verify();
        }
    }

    @Test
    @DisplayName("family_id 부재 레거시 토큰 → familyId=null 로 파싱(전환기 수용, NPE 없음)")
    void familyLessToken_parsesWithNullFamilyId() {
        String legacy = Jwts.builder()
                .header().keyId(KID).and()
                .subject("7").claim("role", "USER")
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith((RSAPrivateKey) keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        StepVerifier.create(verifier(false).verify(legacy))
                .assertNext(claims -> {
                    assertThat(claims.userId()).isEqualTo(7L);
                    assertThat(claims.familyId()).isNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("exp 클레임이 없으면 거부 — 무기한 토큰 차단(GW-2 c1:1)")
    void missingExp_rejected() {
        String noExp = Jwts.builder()
                .header().keyId(KID).and()
                .subject("42").claim("role", "USER")
                .issuedAt(Date.from(Instant.now()))
                .signWith((RSAPrivateKey) keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();

        StepVerifier.create(verifier(false).verify(noExp))
                .expectError(GatewayJwtVerifier.InvalidTokenException.class)
                .verify();
    }
}

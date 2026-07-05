package com.peekcart.global.jwt;

import com.peekcart.global.auth.TokenClaims;
import com.peekcart.global.auth.TokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RS256 발급 서명 + kid 헤더 + 왕복 검증 + 서명 latency 측정 (ADR-0013 D1/D2, 구현 ③ PR1 P2/P5).
 * dev 키쌍(classpath)으로 발급한 토큰을 common-auth verifier 가 kid 로 검증한다.
 * latency 는 KMS 격상(D2 후속) 판단 근거로 p50/p95 를 기록한다(측정만, 전환 미결정).
 */
@DisplayName("JwtTokenSigner RS256 발급 + latency 측정")
class JwtTokenSignerTest {

    private static final String KID = "peekcart-dev-2026";

    private JwtTokenSigner signer;
    private JwtTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        JwtAuthProperties authProps = new JwtAuthProperties(
                "peekcart-secret-key-must-be-at-least-256-bits-long-xxxxxxxxxxxxxxx", 1_800_000, 604_800_000);
        JwtKeyProperties keyProps = new JwtKeyProperties(
                KID,
                // 개인키는 :common testFixtures(test-scope, 산출물 비포함) — 공개키(common main)와 동일 키쌍
                new ClassPathResource("keys/jwt-test-private.pem"),
                List.of(new JwtKeyProperties.PublicKeyEntry(KID, new ClassPathResource("keys/dev-jwt-public.pem"))),
                false);

        signer = new JwtTokenSigner(authProps, keyProps);
        signer.init();

        RsaPublicKeyRegistry registry = new RsaPublicKeyRegistry(keyProps);
        registry.load();
        verifier = new JwtTokenVerifier(authProps, keyProps, registry);
        verifier.init();
    }

    @Test
    @DisplayName("발급 토큰은 RS256(kid) 서명이며 verifier 가 kid 로 검증한다")
    void issue_rs256_verifiedByKid() {
        TokenIssuer.IssuedTokens tokens = signer.issue(42L, "USER");

        // JWT 헤더(첫 세그먼트)에 kid 가 실려야 한다
        String headerJson = new String(java.util.Base64.getUrlDecoder()
                .decode(tokens.accessToken().split("\\.")[0]));
        assertThat(headerJson).contains("\"kid\":\"" + KID + "\"").contains("RS256");

        TokenClaims claims = verifier.parseToken(tokens.accessToken());
        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.role()).isEqualTo("USER");
    }

    @Test
    @DisplayName("RS256 서명 latency p50/p95 측정 (ADR-0013 D2 후속 근거)")
    void signLatency_p50_p95() {
        int warmup = 20;
        int iterations = 200;
        for (int i = 0; i < warmup; i++) {
            signer.issue(1L, "USER");
        }
        List<Long> nanos = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            signer.issue(1L, "USER");
            nanos.add(System.nanoTime() - start);
        }
        Collections.sort(nanos);
        double p50 = nanos.get((int) (iterations * 0.50)) / 1_000_000.0;
        double p95 = nanos.get((int) (iterations * 0.95)) / 1_000_000.0;
        System.out.printf("[RS256 sign latency] p50=%.3fms p95=%.3fms (n=%d, dev key)%n", p50, p95, iterations);

        // 회귀 방어(느슨) — CI 환경 편차 흡수. KMS 격상 판단은 별도 ADR 후보(측정만).
        assertThat(p95).isLessThan(200.0);
    }
}

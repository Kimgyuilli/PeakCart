package com.peekcart.internaltoken;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.function.Consumer;

/**
 * 발행측(gateway)·검증측(common-auth)이 <b>공유하는</b> 내부 토큰 테스트 계약 (계획 P9 교차모듈 conformance).
 *
 * <p>두 모듈은 서로를 의존할 수 없어 하나의 테스트 클래스로 발행↔검증을 잇지 못한다. 대신 <b>버전된
 * 산출물</b>(고정 키쌍 + 커밋된 토큰 + 기대 claims)을 양쪽이 참조한다 —
 * 발행측은 "내가 만든 토큰의 claims 가 이 계약과 같다", 검증측은 "이 계약 토큰을 이렇게 해석한다" 를
 * 각각 고정하므로, 한쪽만 바뀌면 반대편 테스트가 깨진다.
 *
 * <p>servlet/reactor 런타임에 의존하지 않는다(순수 Java) — 양쪽 테스트 classpath 에 안전하게 올라간다.
 */
public final class InternalTokenFixtures {

    private static final String BASE = "/internal-token/";

    /** 승인된 Gateway 서명 키의 kid. */
    public static final String KID = "gw-test-2026";

    /** 계약 토큰의 subject(userId). */
    public static final long USER_ID = 42L;

    /** 계약 토큰의 role. */
    public static final String ROLE = "USER";

    /** 계약 토큰의 refresh family 식별자. */
    public static final String FAMILY_ID = "fam-conformance-0001";

    /** 계약 토큰의 발행 시각. 검증 테스트는 이 시각 기준 Clock 을 주입한다. */
    public static final Instant ISSUED_AT = Instant.ofEpochSecond(1780000000L);

    /** 계약 토큰의 만료 시각(= {@link #ISSUED_AT} + 30초). */
    public static final Instant EXPIRES_AT = Instant.ofEpochSecond(1780000030L);

    private InternalTokenFixtures() {
    }

    /** 승인된 Gateway 개인키(PKCS#8 PEM) — 발행측 테스트 전용. */
    public static String gatewayPrivateKeyPem() {
        return read("gateway-test-private.pem");
    }

    /** 승인된 Gateway 공개키(SPKI PEM) — 검증측 테스트 전용. */
    public static String gatewayPublicKeyPem() {
        return read("gateway-test-public.pem");
    }

    /** 미승인 키(PKCS#8 PEM) — "위조 서명" 음성 테스트용. */
    public static String foreignPrivateKeyPem() {
        return read("foreign-test-private.pem");
    }

    /** 미승인 공개키(SPKI PEM). */
    public static String foreignPublicKeyPem() {
        return read("foreign-test-public.pem");
    }

    /**
     * 커밋된 계약 토큰(RS256, kid={@link #KID}). 발행 구현과 무관하게 고정된 wire 형식이라,
     * 검증측이 이 토큰을 못 읽으면 계약 위반이다.
     */
    public static String conformanceToken() {
        return read("conformance-token.jwt").trim();
    }

    /** 계약 토큰의 payload 원문(JSON) — 발행측이 자기 출력과 대조한다. */
    public static String conformanceClaimsJson() {
        return read("conformance-claims.json").trim();
    }

    /** classpath 리소스 경로(Spring {@code Resource} 등으로 감쌀 때 사용). */
    public static String classpathLocation(String fileName) {
        return BASE + fileName;
    }

    /** 승인 Gateway 개인키. */
    public static RSAPrivateKey gatewayPrivateKey() {
        return privateKey(gatewayPrivateKeyPem());
    }

    /** 승인 Gateway 공개키. */
    public static RSAPublicKey gatewayPublicKey() {
        return publicKey(gatewayPublicKeyPem());
    }

    /** 미승인 개인키 — 위조 서명용. */
    public static RSAPrivateKey foreignPrivateKey() {
        return privateKey(foreignPrivateKeyPem());
    }

    /**
     * 승인 키로 서명한 <b>지금 유효한</b> 내부 토큰을 만든다(현재 시각 기준, 30초 수명).
     * 서비스 통합 테스트가 "Gateway 가 보냈을 법한 토큰" 을 각자 재구현하지 않도록 여기 하나만 둔다.
     *
     * <p>{@link #ISSUED_AT} 이 아니라 <b>현재 시각</b>을 쓴다 — 통합 테스트의 검증기는 실제 시계로 돌기
     * 때문에 고정 시각 토큰은 항상 만료로 판정된다(고정 시각은 conformance vector 전용).
     */
    public static String mint(long userId, String role, String familyId) {
        return signNow(gatewayPrivateKey(), KID, userId, role, familyId);
    }

    /** 미승인 키로 서명한 위조 토큰 — 서명 검증이 실제로 동작하는지 확인하는 음성 대조군. */
    public static String mintForged(long userId, String role) {
        return signNow(foreignPrivateKey(), KID, userId, role, "forged");
    }

    /** 만료된(수명이 이미 지난) 유효 서명 토큰 — 만료 검사가 실제로 동작하는지 확인한다. */
    public static String mintExpired(long userId, String role, String familyId) {
        Instant issuedAt = Instant.now().minusSeconds(600);
        return build(gatewayPrivateKey(), KID, userId, role, familyId, issuedAt, issuedAt.plusSeconds(30));
    }

    private static String signNow(RSAPrivateKey key, String kid, long userId, String role, String familyId) {
        Instant now = Instant.now();
        return build(key, kid, userId, role, familyId, now, now.plusSeconds(30));
    }

    private static String build(RSAPrivateKey key, String kid, long userId, String role, String familyId,
                                Instant issuedAt, Instant expiresAt) {
        JwtBuilder builder = Jwts.builder()
                .header().keyId(kid).and()
                .issuer(InternalTokenContract.ISSUER)
                .subject(String.valueOf(userId))
                .claim(InternalTokenContract.CLAIM_ROLE, role)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt));
        if (familyId != null) {
            builder.claim(InternalTokenContract.CLAIM_FAMILY_ID, familyId);
        }
        return builder.signWith(key, Jwts.SIG.RS256).compact();
    }

    /**
     * 계약 기본값(iss/iat/exp/kid)을 채운 뒤 {@code customizer} 로 변형해 서명한다.
     * 음성 매트릭스가 claim 하나씩만 어긋난 토큰을 만들 때 쓴다.
     */
    public static String sign(RSAPrivateKey key, String kid, Consumer<JwtBuilder> customizer) {
        JwtBuilder builder = Jwts.builder()
                .header().keyId(kid).and()
                .issuer(InternalTokenContract.ISSUER)
                .subject(String.valueOf(USER_ID))
                .claim(InternalTokenContract.CLAIM_ROLE, ROLE)
                .claim(InternalTokenContract.CLAIM_FAMILY_ID, FAMILY_ID)
                .issuedAt(Date.from(ISSUED_AT))
                .expiration(Date.from(EXPIRES_AT));
        customizer.accept(builder);
        return builder.signWith(key, Jwts.SIG.RS256).compact();
    }

    private static RSAPrivateKey privateKey(String pem) {
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der(pem, "PRIVATE KEY")));
        } catch (Exception e) {
            throw new IllegalStateException("fixture 개인키 로드 실패", e);
        }
    }

    private static RSAPublicKey publicKey(String pem) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der(pem, "PUBLIC KEY")));
        } catch (Exception e) {
            throw new IllegalStateException("fixture 공개키 로드 실패", e);
        }
    }

    private static byte[] der(String pem, String type) {
        return Base64.getDecoder().decode(pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", ""));
    }

    private static String read(String fileName) {
        String path = BASE + fileName;
        try (InputStream in = InternalTokenFixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("내부 토큰 fixture 누락: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("내부 토큰 fixture 읽기 실패: " + path, e);
        }
    }
}

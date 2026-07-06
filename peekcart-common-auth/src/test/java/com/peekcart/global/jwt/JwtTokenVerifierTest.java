package com.peekcart.global.jwt;

import com.peekcart.global.auth.TokenClaims;
import com.peekcart.global.auth.TokenParseException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RS256 dual-validation 검증 단위 테스트 (ADR-0013 D1, 구현 ③ PR1 P5).
 * kid 선택 · alg allow-list 거부 · HS256 fallback on/off 를 회귀한다.
 */
@DisplayName("JwtTokenVerifier RS256 dual-validation 단위 테스트")
class JwtTokenVerifierTest {

    private static final String KID = "test-2026";
    private static final String SECRET = "peekcart-secret-key-must-be-at-least-256-bits-long-xxxxxxxxxxxxxxx";

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private final SecretKey hmacKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        this.privateKey = (RSAPrivateKey) pair.getPrivate();
        this.publicKey = (RSAPublicKey) pair.getPublic();
    }

    private JwtTokenVerifier verifier(boolean hs256Fallback) {
        Resource pubPem = new ByteArrayResource(pem("PUBLIC KEY", publicKey.getEncoded()));
        JwtKeyProperties keyProps = new JwtKeyProperties(
                KID, null, List.of(new JwtKeyProperties.PublicKeyEntry(KID, pubPem)), hs256Fallback);
        RsaPublicKeyRegistry registry = new RsaPublicKeyRegistry(keyProps);
        registry.load();
        JwtTokenVerifier verifier = new JwtTokenVerifier(
                new JwtAuthProperties(SECRET, 1_800_000, 604_800_000), keyProps, registry);
        verifier.init();
        return verifier;
    }

    private String rs256Token(String kid, RSAPrivateKey signingKey) {
        return Jwts.builder()
                .header().keyId(kid).and()
                .subject("7").claim("role", "USER").claim("family_id", "family-7")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(signingKey, Jwts.SIG.RS256)
                .compact();
    }

    private String rs256TokenNoKid() {
        return Jwts.builder()
                .subject("7").claim("role", "USER")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    private String hs512Token() {
        return Jwts.builder()
                .subject("7").claim("role", "USER")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(hmacKey, Jwts.SIG.HS512)
                .compact();
    }

    private String hs256Token() {
        // 256bit 이상 키로 HS256 명시 서명 (레거시가 아닌 alg → allow-list 밖)
        SecretKey hs256 = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("7").claim("role", "USER")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(hs256, Jwts.SIG.HS256)
                .compact();
    }

    private String noneAlgToken() {
        return Jwts.builder()
                .subject("7").claim("role", "USER")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .compact();
    }

    @Test
    @DisplayName("RS256 + 등록된 kid: 클레임(family_id 포함)을 파싱한다")
    void rs256_knownKid_parses() {
        TokenClaims claims = verifier(false).parseToken(rs256Token(KID, privateKey));
        assertThat(claims.userId()).isEqualTo(7L);
        assertThat(claims.role()).isEqualTo("USER");
        assertThat(claims.familyId()).isEqualTo("family-7");
    }

    @Test
    @DisplayName("family_id 부재 토큰: familyId=null 로 매핑한다(레거시 안전)")
    void tokenWithoutFamilyId_mapsNull() {
        TokenClaims claims = verifier(true).parseToken(hs512Token());
        assertThat(claims.userId()).isEqualTo(7L);
        assertThat(claims.familyId()).isNull();
    }

    @Test
    @DisplayName("RS256 + 미등록 kid: 거부한다")
    void rs256_unknownKid_rejected() {
        assertThatThrownBy(() -> verifier(false).parseToken(rs256Token("unknown-kid", privateKey)))
                .isInstanceOf(TokenParseException.class);
    }

    @Test
    @DisplayName("RS256 + 다른 개인키 위조 서명: 거부한다")
    void rs256_forgedSignature_rejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPrivateKey attacker = (RSAPrivateKey) generator.generateKeyPair().getPrivate();
        assertThatThrownBy(() -> verifier(false).parseToken(rs256Token(KID, attacker)))
                .isInstanceOf(TokenParseException.class);
    }

    @Test
    @DisplayName("RS256 + kid 헤더 부재: 거부한다")
    void rs256_noKid_rejected() {
        assertThatThrownBy(() -> verifier(false).parseToken(rs256TokenNoKid()))
                .isInstanceOf(TokenParseException.class);
    }

    @Test
    @DisplayName("HS512(레거시) + fallback ON: 전환기 대칭키를 수용한다")
    void hs512_fallbackOn_accepted() {
        TokenClaims claims = verifier(true).parseToken(hs512Token());
        assertThat(claims.userId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("HS512(레거시) + fallback OFF: allow-list 위반으로 거부한다")
    void hs512_fallbackOff_rejected() {
        assertThatThrownBy(() -> verifier(false).parseToken(hs512Token()))
                .isInstanceOf(TokenParseException.class);
    }

    @Test
    @DisplayName("HS256 + fallback ON: 레거시 alg(HS512) 이 아니므로 거부한다(allow-list 정밀)")
    void hs256_fallbackOn_rejected() {
        assertThatThrownBy(() -> verifier(true).parseToken(hs256Token()))
                .isInstanceOf(TokenParseException.class);
    }

    @Test
    @DisplayName("alg=none(무서명): 거부한다")
    void noneAlg_rejected() {
        assertThatThrownBy(() -> verifier(true).parseToken(noneAlgToken()))
                .isInstanceOf(TokenParseException.class);
    }

    private static byte[] pem(String type, byte[] der) {
        String body = Base64.getEncoder().encodeToString(der);
        return ("-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}

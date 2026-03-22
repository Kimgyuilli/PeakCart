package com.peekcart.global.jwt;

import com.peekcart.global.auth.TokenClaims;
import com.peekcart.global.auth.TokenIssuer;
import com.peekcart.global.auth.TokenParseException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 액세스 토큰의 생성, 파싱, 유효성 검증을 담당하는 컴포넌트.
 * {@link TokenIssuer}를 구현하여 액세스 토큰과 리프레시 토큰 쌍을 함께 발급한다.
 */
@Component
public class JwtProvider implements TokenIssuer {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.access-token-expiry}")
    private long accessTokenExpiry;

    @Value("${app.jwt.refresh-token-expiry}")
    private long refreshTokenExpiry;

    private SecretKey key;

    @PostConstruct
    private void init() {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 액세스 토큰(JWT)과 리프레시 토큰(UUID)을 함께 발급한다.
     */
    @Override
    public IssuedTokens issue(Long userId, String role) {
        String accessToken = createAccessToken(userId, role);
        String refreshTokenValue = UUID.randomUUID().toString();
        LocalDateTime refreshTokenExpiresAt = LocalDateTime.now().plusSeconds(refreshTokenExpiry / 1000);
        return new IssuedTokens(accessToken, refreshTokenValue, refreshTokenExpiresAt);
    }

    /**
     * 액세스 토큰을 파싱하여 {@link TokenClaims}로 반환한다.
     * jjwt의 {@code Claims} 타입을 외부에 노출하지 않는다.
     *
     * @throws TokenParseException 서명이 유효하지 않거나 토큰이 만료된 경우
     */
    @Override
    public TokenClaims parseToken(String token) {
        try {
            Claims claims = parseClaims(token);
            return new TokenClaims(
                    Long.parseLong(claims.getSubject()),
                    claims.get("role", String.class),
                    claims.getExpiration().toInstant()
            );
        } catch (JwtException | IllegalArgumentException e) {
            throw new TokenParseException(e);
        }
    }

    private String createAccessToken(Long userId, String role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiry))
                .signWith(key)
                .compact();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

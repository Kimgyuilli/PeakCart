package com.peekcart.global.jwt;

import com.peekcart.global.auth.TokenIssuer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

    /** 설정된 시크릿을 HMAC-SHA 키로 변환한다. */
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 액세스 토큰(JWT)과 리프레시 토큰(UUID)을 함께 발급한다.
     *
     * @param userId 사용자 PK
     * @param role   사용자 역할 (예: "USER", "ADMIN")
     * @return 발급된 토큰 쌍 및 리프레시 토큰 만료 시각
     */
    @Override
    public IssuedTokens issue(Long userId, String role) {
        String accessToken = createAccessToken(userId, role);
        String refreshTokenValue = UUID.randomUUID().toString();
        LocalDateTime refreshTokenExpiresAt = LocalDateTime.now().plusSeconds(refreshTokenExpiry / 1000);
        return new IssuedTokens(accessToken, refreshTokenValue, refreshTokenExpiresAt);
    }

    /**
     * 사용자 ID와 역할을 클레임으로 담은 액세스 토큰을 생성한다.
     *
     * @param userId 사용자 PK
     * @param role   사용자 역할 (예: "USER", "ADMIN")
     * @return 서명된 JWT 문자열
     */
    private String createAccessToken(Long userId, String role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiry))
                .signWith(getKey())
                .compact();
    }

    /**
     * 토큰을 파싱하여 클레임을 반환한다.
     *
     * @param token JWT 문자열
     * @return 파싱된 {@link Claims}
     * @throws io.jsonwebtoken.JwtException 토큰이 유효하지 않을 경우
     */
    @Override
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 토큰의 서명과 만료 여부를 검사한다.
     *
     * @param token JWT 문자열
     * @return 유효하면 {@code true}
     */
    @Override
    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}

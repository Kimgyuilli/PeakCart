package com.peekcart.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 액세스 토큰의 생성, 파싱, 유효성 검증을 담당하는 컴포넌트.
 */
@Component
public class JwtProvider {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.access-token-expiry}")
    private long accessTokenExpiry;

    /** 설정된 시크릿을 HMAC-SHA 키로 변환한다. */
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 사용자 ID와 역할을 클레임으로 담은 액세스 토큰을 생성한다.
     *
     * @param userId 사용자 PK
     * @param role   사용자 역할 (예: "USER", "ADMIN")
     * @return 서명된 JWT 문자열
     */
    public String createAccessToken(Long userId, String role) {
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
    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}

package com.peekcart.global.jwt;

import com.peekcart.global.auth.TokenIssuer;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPrivateKey;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 액세스 토큰 발급(sign) 전용 컴포넌트 (ADR-0013 D1 · ADR-0014 D1-b sign, User 전속).
 * <p>발급은 <b>RS256 개인키 단일</b>로 서명하고 JWT 헤더에 {@code kid}({@link JwtKeyProperties#activeKid()})
 * 를 기록한다. 검증(verify)은 common-auth {@code JwtTokenVerifier}가 공개키({@code kid} 선택)로 수행한다.
 * 개인키는 User 서명 서버만 로드한다(다른 서비스는 {@code privateKeyLocation} 미설정).
 */
@Component
public class JwtTokenSigner implements TokenIssuer {

    private final JwtAuthProperties properties;
    private final JwtKeyProperties keyProperties;
    private RSAPrivateKey privateKey;

    public JwtTokenSigner(JwtAuthProperties properties, JwtKeyProperties keyProperties) {
        this.properties = properties;
        this.keyProperties = keyProperties;
    }

    @PostConstruct
    void init() {
        if (keyProperties.activeKid() == null || keyProperties.privateKeyLocation() == null) {
            throw new IllegalStateException(
                    "User 발급 서버는 app.jwt.rs256.active-kid + private-key-location 설정이 필요합니다 (ADR-0013 D1)");
        }
        this.privateKey = PemKeyLoader.loadPrivateKey(keyProperties.privateKeyLocation());
    }

    @Override
    public IssuedTokens issue(Long userId, String role) {
        String accessToken = Jwts.builder()
                .header().keyId(keyProperties.activeKid()).and()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + properties.accessTokenExpiry()))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
        String refreshTokenValue = UUID.randomUUID().toString();
        LocalDateTime refreshTokenExpiresAt = LocalDateTime.now().plusSeconds(properties.refreshTokenExpiry() / 1000);
        return new IssuedTokens(accessToken, refreshTokenValue, refreshTokenExpiresAt);
    }
}

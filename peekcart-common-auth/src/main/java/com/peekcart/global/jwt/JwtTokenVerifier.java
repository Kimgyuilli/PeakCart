package com.peekcart.global.jwt;

import com.peekcart.global.auth.TokenClaims;
import com.peekcart.global.auth.TokenParseException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;

/**
 * JWT 액세스 토큰의 파싱·서명검증·만료검증을 담당하는 검증 전용 컴포넌트 (ADR-0013 D1, ADR-0014 D1-b verify).
 * <p><b>dual-validation(전환기)</b>: 토큰 헤더 {@code alg}·{@code kid} 로 키를 선택한다.
 * <ul>
 *   <li>{@code RS256} → {@link RsaPublicKeyRegistry} 에서 kid 로 공개키 선택. kid 부재/unknown 이면 거부.</li>
 *   <li>{@code HS256} → {@link JwtKeyProperties#hs256FallbackEnabled()} 일 때만 대칭키로 수용(bounded).</li>
 *   <li>그 외(alg=none 등) → allow-list 위반으로 거부.</li>
 * </ul>
 * jjwt {@code Claims} 타입을 외부에 노출하지 않고 {@link TokenClaims}로 캡슐화한다.
 * 발급(sign)은 User 전속 {@code JwtTokenSigner}가 담당한다.
 */
@Component
public class JwtTokenVerifier {

    private final JwtAuthProperties properties;
    private final JwtKeyProperties keyProperties;
    private final RsaPublicKeyRegistry publicKeyRegistry;
    private SecretKey hs256Key;

    public JwtTokenVerifier(JwtAuthProperties properties,
                            JwtKeyProperties keyProperties,
                            RsaPublicKeyRegistry publicKeyRegistry) {
        this.properties = properties;
        this.keyProperties = keyProperties;
        this.publicKeyRegistry = publicKeyRegistry;
    }

    @PostConstruct
    void init() {
        this.hs256Key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 액세스 토큰을 파싱하여 {@link TokenClaims}로 반환한다.
     *
     * @throws TokenParseException 서명이 유효하지 않거나 토큰이 만료되었거나 허용되지 않은 alg/kid 인 경우
     */
    public TokenClaims parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .keyLocator(keyLocator())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new TokenClaims(
                    Long.parseLong(claims.getSubject()),
                    claims.get("role", String.class),
                    claims.get("family_id", String.class),
                    claims.getExpiration().toInstant()
            );
        } catch (JwtException | IllegalArgumentException e) {
            throw new TokenParseException(e);
        }
    }

    /**
     * 헤더 {@code alg}/{@code kid} 로 검증 키를 선택하며 allow-list(RS256 · 전환기 HS256)를 강제한다.
     * unknown kid / 미허용 alg 는 {@link UnsupportedJwtException} 로 거부된다.
     */
    private Locator<Key> keyLocator() {
        return header -> {
            ProtectedHeader jwsHeader = (ProtectedHeader) header;
            String alg = jwsHeader.getAlgorithm();
            if ("RS256".equals(alg)) {
                String kid = jwsHeader.getKeyId();
                Key key = publicKeyRegistry.find(kid);
                if (key == null) {
                    throw new UnsupportedJwtException("알 수 없는 kid: " + kid);
                }
                return key;
            }
            // 전환기 대칭키 fallback — 레거시 토큰은 512bit 시크릿상 HS512 단일이므로 정확히 그것만 수용한다
            // (HS256/HS384 등 다른 HMAC 은 allow-list 밖으로 거부). fallback 은 PR4 에서 제거된다.
            if ("HS512".equals(alg) && keyProperties.hs256FallbackEnabled()) {
                return hs256Key;
            }
            throw new UnsupportedJwtException("허용되지 않은 서명 알고리즘: " + alg);
        };
    }
}

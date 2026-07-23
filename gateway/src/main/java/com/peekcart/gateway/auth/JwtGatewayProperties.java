package com.peekcart.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Gateway JWT 검증 설정 (ADR-0013 D1/D3 · 구현 ③ PR3a).
 *
 * <p>ADR-0007: 동작 규약(알고리즘 allow-list·fallback 스위치·TTL)은 base `application.yml` 에 두고,
 * 환경마다 달라지는 값은 {@code jwksUri} 뿐이라 환경변수 placeholder 로 주입한다.
 *
 * @param jwksUri              User JWKS 정본 URI. 공개키 소스는 여기 하나뿐(로컬 미러 금지, ADR-0013 D1)
 * @param jwksTimeout          JWKS 조회 타임아웃
 * @param jwksRefreshCooldown  unknown kid 폭주 시 refresh 최소 간격
 * @param jwksRefreshInterval  주기 갱신 간격
 * @param hs512FallbackEnabled 전환기 HMAC(HS512) fallback 스위치. 기본 off, PR4(P22)에서 제거.
 *                             레거시 토큰의 실제 alg 는 HS512 다(HS256 아님 — JwtTokenVerifier:90-95)
 * @param hs512Secret          fallback 활성 시에만 사용하는 대칭키
 */
@ConfigurationProperties(prefix = "app.gateway.jwt")
public record JwtGatewayProperties(
        String jwksUri,
        Duration jwksTimeout,
        Duration jwksRefreshCooldown,
        Duration jwksRefreshInterval,
        boolean hs512FallbackEnabled,
        String hs512Secret
) {
    public JwtGatewayProperties {
        jwksTimeout = jwksTimeout != null ? jwksTimeout : Duration.ofSeconds(2);
        jwksRefreshCooldown = jwksRefreshCooldown != null ? jwksRefreshCooldown : Duration.ofSeconds(10);
        jwksRefreshInterval = jwksRefreshInterval != null ? jwksRefreshInterval : Duration.ofMinutes(5);
    }
}

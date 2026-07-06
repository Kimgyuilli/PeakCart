package com.peekcart.global.auth;

import java.time.LocalDateTime;

/**
 * 액세스 토큰 발급(sign)을 제공하는 추상화 (ADR-0014 D1-b, User 전속).
 * Application 레이어가 JWT 구현 세부사항에 의존하지 않도록 역전시킨다.
 * 검증(parse/verify)은 common-auth {@code JwtTokenVerifier}로 분리됐다.
 */
public interface TokenIssuer {

    /**
     * 사용자 ID·역할·family 로 토큰 쌍을 발급한다.
     * 액세스 토큰에는 {@code family_id} 클레임이 실린다(ADR-0013 D4 reuse detection).
     *
     * @param userId   사용자 PK
     * @param role     사용자 역할 (예: "USER", "ADMIN")
     * @param familyId 리프레시 토큰 family (login/signup=신규, refresh=기존 승계)
     * @return 발급된 토큰 쌍
     */
    IssuedTokens issue(Long userId, String role, String familyId);

    /**
     * 발급된 액세스 토큰과 리프레시 토큰 정보를 담는 값 객체.
     *
     * @param accessToken            서명된 JWT 액세스 토큰
     * @param refreshTokenValue      DB에 저장할 리프레시 토큰 값 (UUID)
     * @param refreshTokenExpiresAt  리프레시 토큰 만료 시각
     */
    record IssuedTokens(String accessToken, String refreshTokenValue, LocalDateTime refreshTokenExpiresAt) {}
}

package com.peekcart.global.auth;

import io.jsonwebtoken.Claims;

import java.time.LocalDateTime;

/**
 * 액세스 토큰 발급 · 검증 · 파싱을 제공하는 추상화.
 * Application 레이어가 JWT 구현 세부사항에 의존하지 않도록 역전시킨다.
 */
public interface TokenIssuer {

    /**
     * 사용자 ID와 역할로 토큰 쌍을 발급한다.
     *
     * @param userId 사용자 PK
     * @param role   사용자 역할 (예: "USER", "ADMIN")
     * @return 발급된 토큰 쌍
     */
    IssuedTokens issue(Long userId, String role);

    /**
     * 액세스 토큰의 서명과 만료 여부를 검사한다.
     *
     * @param token JWT 문자열
     * @return 유효하면 {@code true}
     */
    boolean isValid(String token);

    /**
     * 액세스 토큰을 파싱하여 클레임을 반환한다.
     *
     * @param token JWT 문자열
     * @return 파싱된 {@link Claims}
     */
    Claims parseToken(String token);

    /**
     * 발급된 액세스 토큰과 리프레시 토큰 정보를 담는 값 객체.
     *
     * @param accessToken            서명된 JWT 액세스 토큰
     * @param refreshTokenValue      DB에 저장할 리프레시 토큰 값 (UUID)
     * @param refreshTokenExpiresAt  리프레시 토큰 만료 시각
     */
    record IssuedTokens(String accessToken, String refreshTokenValue, LocalDateTime refreshTokenExpiresAt) {}
}

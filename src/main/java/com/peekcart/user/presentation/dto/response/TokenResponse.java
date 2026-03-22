package com.peekcart.user.presentation.dto.response;

/**
 * 액세스 토큰과 리프레시 토큰을 담는 응답 DTO.
 */
public record TokenResponse(
        String accessToken,
        String refreshToken
) {}

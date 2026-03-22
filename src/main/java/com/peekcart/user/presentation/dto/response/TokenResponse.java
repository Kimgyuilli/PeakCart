package com.peekcart.user.presentation.dto.response;

public record TokenResponse(
        String accessToken,
        String refreshToken
) {}

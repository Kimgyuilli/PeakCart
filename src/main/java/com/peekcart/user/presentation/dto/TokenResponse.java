package com.peekcart.user.presentation.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken
) {}

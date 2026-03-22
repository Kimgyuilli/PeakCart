package com.peekcart.user.presentation.dto.response;

public record UserResponse(
        Long id,
        String email,
        String name,
        String role
) {}

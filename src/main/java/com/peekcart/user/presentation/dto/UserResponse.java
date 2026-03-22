package com.peekcart.user.presentation.dto;

public record UserResponse(
        Long id,
        String email,
        String name,
        String role
) {}

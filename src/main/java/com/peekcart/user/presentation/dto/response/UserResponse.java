package com.peekcart.user.presentation.dto.response;

/**
 * 회원 정보 응답 DTO.
 */
public record UserResponse(
        Long id,
        String email,
        String name,
        String role
) {}

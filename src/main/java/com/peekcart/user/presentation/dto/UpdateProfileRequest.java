package com.peekcart.user.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(
        @NotBlank String name
) {}

package com.peekcart.user.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(
        @NotBlank String name
) {}

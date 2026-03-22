package com.peekcart.user.presentation.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8) String password,
        @NotBlank String name
) {}

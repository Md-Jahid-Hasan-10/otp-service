package com.banking.otp.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SendOtpRequest(
        @NotNull(message = "userId is required")
        Long userId,

        @NotBlank(message = "email is required")
        @Email(message = "email format is invalid")
        String email,

        @NotBlank(message = "purpose is required")
        @Size(max = 50, message = "purpose must be at most 50 characters")
        String purpose,

        @Size(max = 100, message = "referenceId must be at most 100 characters")
        String referenceId
) {}
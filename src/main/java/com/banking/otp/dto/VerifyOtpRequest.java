package com.banking.otp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyOtpRequest(
        @NotBlank(message = "otpId is required")
        String otpId,

        @NotBlank(message = "otp is required")
        @Pattern(regexp = "\\d{6}", message = "otp must be exactly 6 digits")
        String otp,

        @Size(max = 100, message = "referenceId must be at most 100 characters")
        String referenceId
) {}
package com.banking.otp.dto;

public record VerifyOtpResponse(boolean verified, String message) {}
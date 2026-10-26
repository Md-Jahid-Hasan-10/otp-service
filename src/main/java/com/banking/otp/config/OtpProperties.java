package com.banking.otp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.otp")
public record OtpProperties(
        int expiryMinutes,
        int maxAttempts,
        int resendCooldownSeconds,
        String hashSecret,
        boolean logDevOtp
) {}
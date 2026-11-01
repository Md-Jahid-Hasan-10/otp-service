package com.banking.otp.messaging.payload;

import java.time.Instant;

public record OtpIssuedPayload(
        String transferId,
        String otpId,
        String email,
        String encryptedCode,
        Instant expiresAt
) {}
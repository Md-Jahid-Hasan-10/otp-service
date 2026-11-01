package com.banking.otp.messaging.payload;

public record OtpIssueRejectedPayload(String transferId, String reasonCode, long retryAfterSeconds) {}
package com.banking.otp.messaging.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IssueOtpPayload(String transferId, Long userId, String email, String purpose) {}
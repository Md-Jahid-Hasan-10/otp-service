package com.banking.otp.store;

public record VerifyResult(Outcome outcome, int remainingAttempts) {

    public enum Outcome {
        VERIFIED,
        NOT_FOUND,
        LOCKED,
        WRONG_REFERENCE,
        WRONG_OTP
    }
}
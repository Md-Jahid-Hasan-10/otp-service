package com.banking.otp.store;

import java.time.Duration;
import java.util.UUID;

public interface OtpStore {

    /** Returns false while the resend cooldown for this user, purpose and reference is still running. */
    boolean tryStartCooldown(Long userId, String purpose, String referenceId, Duration cooldown);

    /** Stores a new OTP hash and removes any older active OTP for the same user, purpose and reference. */
    void save(UUID otpId, Long userId, String purpose, String referenceId, String otpHash, Duration ttl);

    /** Checks the OTP atomically. A correct OTP is consumed, so it works only once. */
    VerifyResult verify(UUID otpId, String presentedHash, String referenceId, int maxAttempts);

    /** Removes an OTP that was just created, used when the email could not be sent. */
    void discard(UUID otpId, Long userId, String purpose, String referenceId);
}
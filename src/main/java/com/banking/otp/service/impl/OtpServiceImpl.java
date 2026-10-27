package com.banking.otp.service.impl;

import com.banking.otp.client.NotificationClient;
import com.banking.otp.config.OtpProperties;
import com.banking.otp.dto.SendOtpRequest;
import com.banking.otp.dto.SendOtpResponse;
import com.banking.otp.dto.VerifyOtpRequest;
import com.banking.otp.dto.VerifyOtpResponse;
import com.banking.otp.exception.NotificationUnavailableException;
import com.banking.otp.exception.TooManyRequestsException;
import com.banking.otp.service.OtpService;
import com.banking.otp.store.OtpStore;
import com.banking.otp.store.VerifyResult;
import com.banking.otp.util.OtpGenerator;
import com.banking.otp.util.OtpHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
public class OtpServiceImpl implements OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpServiceImpl.class);

    private final OtpStore store;
    private final OtpGenerator generator;
    private final OtpHasher hasher;
    private final NotificationClient notificationClient;
    private final OtpProperties properties;

    public OtpServiceImpl(OtpStore store,
                          OtpGenerator generator,
                          OtpHasher hasher,
                          NotificationClient notificationClient,
                          OtpProperties properties) {
        this.store = store;
        this.generator = generator;
        this.hasher = hasher;
        this.notificationClient = notificationClient;
        this.properties = properties;
    }

    @Override
    public SendOtpResponse send(SendOtpRequest request) {
        String purpose = request.purpose().trim().toUpperCase();
        String referenceId = request.referenceId() == null ? "" : request.referenceId().trim();
        Long userId = request.userId();

        if (!store.tryStartCooldown(userId, purpose, referenceId,
                Duration.ofSeconds(properties.resendCooldownSeconds()))) {
            throw new TooManyRequestsException(
                    "Please wait " + properties.resendCooldownSeconds() + " seconds before requesting a new OTP");
        }

        String otp = generator.generate();
        UUID otpId = UUID.randomUUID();

        // Only the keyed hash is stored, never the OTP itself.
        store.save(otpId, userId, purpose, referenceId, hasher.hash(otpId, otp),
                Duration.ofMinutes(properties.expiryMinutes()));

        try {
            notificationClient.sendOtp(request.email(), otp);
        } catch (NotificationUnavailableException ex) {
            // The user never received the code. Remove it so the cooldown does not block a retry.
            store.discard(otpId, userId, purpose, referenceId);
            throw ex;
        }

        if (properties.logDevOtp()) {
            log.warn("DEV ONLY - OTP for otpId {} is {}", otpId, otp);
        }

        return new SendOtpResponse(otpId.toString(), "OTP sent to your email");
    }

    @Override
    public VerifyOtpResponse verify(VerifyOtpRequest request) {
        UUID otpId;
        try {
            otpId = UUID.fromString(request.otpId().trim());
        } catch (IllegalArgumentException ex) {
            return fail("Invalid OTP");
        }

        String reference = request.referenceId() == null ? null : request.referenceId().trim();
        String presentedHash = hasher.hash(otpId, request.otp());

        VerifyResult result = store.verify(otpId, presentedHash, reference, properties.maxAttempts());

        return switch (result.outcome()) {
            case VERIFIED -> new VerifyOtpResponse(true, "OTP verified");
            case NOT_FOUND -> fail("OTP expired or invalid. Request a new one");
            case LOCKED -> fail("Too many wrong attempts. Request a new OTP");
            case WRONG_REFERENCE -> fail("OTP does not belong to this request");
            case WRONG_OTP -> result.remainingAttempts() > 0
                    ? fail("Invalid OTP. " + result.remainingAttempts() + " attempt(s) remaining")
                    : fail("Too many wrong attempts. Request a new OTP");
        };
    }

    private VerifyOtpResponse fail(String message) {
        return new VerifyOtpResponse(false, message);
    }
}
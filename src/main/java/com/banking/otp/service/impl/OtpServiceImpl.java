package com.banking.otp.service.impl;

import com.banking.otp.client.NotificationClient;
import com.banking.otp.config.OtpProperties;
import com.banking.otp.dto.SendOtpRequest;
import com.banking.otp.dto.SendOtpResponse;
import com.banking.otp.dto.VerifyOtpRequest;
import com.banking.otp.dto.VerifyOtpResponse;
import com.banking.otp.entity.OtpRecord;
import com.banking.otp.exception.NotificationUnavailableException;
import com.banking.otp.exception.TooManyRequestsException;
import com.banking.otp.repository.OtpRecordRepository;
import com.banking.otp.service.OtpService;
import com.banking.otp.util.OtpGenerator;
import com.banking.otp.util.OtpHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class OtpServiceImpl implements OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpServiceImpl.class);

    private final OtpRecordRepository repository;
    private final OtpGenerator generator;
    private final OtpHasher hasher;
    private final NotificationClient notificationClient;
    private final OtpProperties properties;

    public OtpServiceImpl(OtpRecordRepository repository,
                          OtpGenerator generator,
                          OtpHasher hasher,
                          NotificationClient notificationClient,
                          OtpProperties properties) {
        this.repository = repository;
        this.generator = generator;
        this.hasher = hasher;
        this.notificationClient = notificationClient;
        this.properties = properties;
    }

    @Override
    public SendOtpResponse send(SendOtpRequest request) {
        String purpose = request.purpose().trim().toUpperCase();
        String referenceId = request.referenceId() == null ? "" : request.referenceId().trim();
        LocalDateTime now = LocalDateTime.now();

        Optional<OtpRecord> latest = repository
                .findTopByUserIdAndPurposeAndReferenceIdOrderByCreatedAtDesc(
                        request.userId(), purpose, referenceId);
        if (latest.isPresent()
                && latest.get().getCreatedAt().isAfter(now.minusSeconds(properties.resendCooldownSeconds()))) {
            throw new TooManyRequestsException(
                    "Please wait " + properties.resendCooldownSeconds() + " seconds before requesting a new OTP");
        }

        // Only the newest OTP for this user, purpose and reference can ever be valid.
        repository.invalidateActive(request.userId(), purpose, referenceId);

        String otp = generator.generate();
        UUID otpId = UUID.randomUUID();

        OtpRecord record = new OtpRecord();
        record.setId(otpId);
        record.setUserId(request.userId());
        record.setPurpose(purpose);
        record.setReferenceId(referenceId);
        record.setOtpHash(hasher.hash(otpId, otp));
        record.setExpiresAt(now.plusMinutes(properties.expiryMinutes()));
        record.setAttempts(0);
        record.setUsed(false);
        repository.save(record);

        try {
            notificationClient.sendOtp(request.email(), otp);
        } catch (NotificationUnavailableException ex) {
            // The user never received the code, so the record is useless. Remove it
            // so the cooldown does not block an immediate retry.
            repository.deleteById(otpId);
            throw ex;
        }

        if (properties.logDevOtp()) {
            log.warn("DEV ONLY - OTP for otpId {} is {}", otpId, otp);
        }

        return new SendOtpResponse(otpId.toString(), "OTP sent to your email");
    }

    @Override
    @Transactional
    public VerifyOtpResponse verify(VerifyOtpRequest request) {
        UUID otpId;
        try {
            otpId = UUID.fromString(request.otpId().trim());
        } catch (IllegalArgumentException ex) {
            return fail("Invalid OTP");
        }

        Optional<OtpRecord> found = repository.findByIdForUpdate(otpId);
        if (found.isEmpty()) {
            return fail("Invalid OTP");
        }
        OtpRecord record = found.get();

        if (record.isUsed()) {
            return fail("OTP already used or replaced. Request a new one");
        }
        if (record.getExpiresAt().isBefore(LocalDateTime.now())) {
            return fail("OTP expired. Request a new one");
        }
        if (record.getAttempts() >= properties.maxAttempts()) {
            return fail("Too many wrong attempts. Request a new OTP");
        }
        if (request.referenceId() != null
                && !request.referenceId().trim().equals(record.getReferenceId())) {
            return fail("OTP does not belong to this request");
        }

        if (!hasher.matches(otpId, request.otp(), record.getOtpHash())) {
            record.setAttempts(record.getAttempts() + 1);
            repository.save(record);
            int remaining = properties.maxAttempts() - record.getAttempts();
            return remaining > 0
                    ? fail("Invalid OTP. " + remaining + " attempt(s) remaining")
                    : fail("Too many wrong attempts. Request a new OTP");
        }

        record.setUsed(true);
        repository.save(record);
        return new VerifyOtpResponse(true, "OTP verified");
    }

    private VerifyOtpResponse fail(String message) {
        return new VerifyOtpResponse(false, message);
    }
}
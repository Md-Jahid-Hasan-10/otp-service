package com.banking.otp.messaging;

import com.banking.otp.config.OtpProperties;
import com.banking.otp.messaging.payload.IssueOtpPayload;
import com.banking.otp.messaging.payload.OtpIssueRejectedPayload;
import com.banking.otp.messaging.payload.OtpIssuedPayload;
import com.banking.otp.store.OtpStore;
import com.banking.otp.util.OtpGenerator;
import com.banking.otp.util.OtpHasher;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class IssueOtpHandler {

    private static final Logger log = LoggerFactory.getLogger(IssueOtpHandler.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Duration COMMAND_MEMORY = Duration.ofHours(1);

    private final OtpStore store;
    private final OtpGenerator generator;
    private final OtpHasher hasher;
    private final EventCrypto crypto;
    private final DirectEventPublisher publisher;
    private final OtpProperties properties;
    private final MeterRegistry meters;

    public IssueOtpHandler(OtpStore store,
                           OtpGenerator generator,
                           OtpHasher hasher,
                           EventCrypto crypto,
                           DirectEventPublisher publisher,
                           OtpProperties properties,
                           MeterRegistry meters) {
        this.store = store;
        this.generator = generator;
        this.hasher = hasher;
        this.crypto = crypto;
        this.publisher = publisher;
        this.properties = properties;
        this.meters = meters;
    }

    public void handle(EventEnvelope command, IssueOtpPayload payload) {
        validate(payload);

        String commandId = command.eventId();
        if (store.isCommandHandled(commandId)) {
            count("duplicate");
            return;
        }

        String transferId = payload.transferId().trim();
        String purpose = payload.purpose().trim().toUpperCase();
        Long userId = payload.userId();

        long wait = store.cooldownRemainingSeconds(userId, purpose, transferId);
        if (wait > 0) {
            publisher.publish(TopicNames.OTP_EVENTS, transferId, "OtpIssueRejected", transferId, commandId,
                    new OtpIssueRejectedPayload(transferId, "COOLDOWN", wait));
            store.markCommandHandled(commandId, COMMAND_MEMORY);
            count("rejected");
            return;
        }

        String otp = generator.generate();
        UUID otpId = UUID.randomUUID();
        Duration ttl = Duration.ofMinutes(properties.expiryMinutes());

        // Only the keyed hash is stored. Saving also removes any older OTP for this transfer.
        store.save(otpId, userId, purpose, transferId, hasher.hash(otpId, otp), ttl);

        // If publishing fails, an exception stops here and the command is retried:
        // the cooldown and the command marker are only set after a successful publish.
        publisher.publish(TopicNames.OTP_EVENTS, transferId, "OtpIssued", transferId, commandId,
                new OtpIssuedPayload(transferId, otpId.toString(), payload.email().trim(),
                        crypto.encrypt(otp, transferId), Instant.now().plus(ttl)));

        store.tryStartCooldown(userId, purpose, transferId,
                Duration.ofSeconds(properties.resendCooldownSeconds()));
        store.markCommandHandled(commandId, COMMAND_MEMORY);
        count("issued");

        if (properties.logDevOtp()) {
            log.warn("DEV ONLY - OTP for transfer {} (otpId {}) is {}", transferId, otpId, otp);
        }
    }

    private void validate(IssueOtpPayload payload) {
        if (isBlank(payload.transferId()) || payload.transferId().length() > 100) {
            throw new InvalidEventException("IssueOtp: transferId is missing or too long");
        }
        if (payload.userId() == null) {
            throw new InvalidEventException("IssueOtp: userId is missing");
        }
        if (isBlank(payload.email()) || !EMAIL.matcher(payload.email().trim()).matches()) {
            throw new InvalidEventException("IssueOtp: email is missing or invalid");
        }
        if (isBlank(payload.purpose()) || payload.purpose().length() > 50) {
            throw new InvalidEventException("IssueOtp: purpose is missing or too long");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void count(String result) {
        meters.counter("otp.issued", "result", result).increment();
    }
}
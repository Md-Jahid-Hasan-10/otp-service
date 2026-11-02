package com.banking.otp.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Prints the fingerprint of the event key at startup.
 *
 * The OTP code is encrypted here and decrypted in the notification service.
 * When the two services use different keys, the code cannot be read, the
 * email is never sent, and the message is parked in the dead-letter topic
 * without a clear reason. The fingerprint makes that visible: the two
 * services have to show the same value.
 */
@Component
public class EventCryptoFingerprintLogger {

    private static final Logger log = LoggerFactory.getLogger(EventCryptoFingerprintLogger.class);

    private final EventCrypto crypto;

    public EventCryptoFingerprintLogger(EventCrypto crypto) {
        this.crypto = crypto;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logFingerprint() {
        log.info("Event crypto key fingerprint: {}", crypto.fingerprint());
    }
}

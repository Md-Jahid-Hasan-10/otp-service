package com.banking.otp.util;

import com.banking.otp.config.OtpProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class OtpHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec keySpec;

    public OtpHasher(OtpProperties properties) {
        this.keySpec = new SecretKeySpec(
                properties.hashSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String hash(UUID otpId, String otp) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(keySpec);
            byte[] result = mac.doFinal((otpId + ":" + otp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(result);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not hash OTP", ex);
        }
    }

    public boolean matches(UUID otpId, String otp, String expectedHash) {
        return MessageDigest.isEqual(
                hash(otpId, otp).getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
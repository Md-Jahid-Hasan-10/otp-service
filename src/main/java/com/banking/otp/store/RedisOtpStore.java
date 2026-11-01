
package com.banking.otp.store;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class RedisOtpStore implements OtpStore {

    private static final String OTP_PREFIX = "otp:";

    // KEYS: 1 = otp key, 2 = active pointer key.
    // ARGV: 1 user, 2 purpose, 3 reference, 4 otp hash,
    // 5 ttl in ms, 6 otpId, 7 otp key prefix.
    private static final String SAVE_SCRIPT = """
            local old = redis.call('GET', KEYS[2])
            if old then redis.call('DEL', ARGV[7] .. old) end
            redis.call('HSET', KEYS[1], 'user', ARGV[1], 'purpose', ARGV[2], 'ref', ARGV[3], 'hash', ARGV[4], 'attempts', '0')
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            redis.call('SET', KEYS[2], ARGV[6], 'PX', ARGV[5])
            return 1
            """;

    // KEYS: 1 = otp key.
    // ARGV: 1 presented hash, 2 max attempts, 3 reference ('' = not checked).
    // Returns "code:remaining": 0 not found, 1 locked,
    // 2 wrong reference, 3 verified, 4 wrong otp.
    private static final String VERIFY_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return '0:0' end
            local max = tonumber(ARGV[2])
            local attempts = tonumber(redis.call('HGET', KEYS[1], 'attempts'))
            if attempts >= max then return '1:0' end
            if ARGV[3] ~= '' and redis.call('HGET', KEYS[1], 'ref') ~= ARGV[3] then return '2:0' end
            if redis.call('HGET', KEYS[1], 'hash') == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return '3:0'
            end
            attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            return '4:' .. (max - attempts)
            """;

    private final StringRedisTemplate redis;

    private final DefaultRedisScript<Long> saveScript =
            new DefaultRedisScript<>(SAVE_SCRIPT, Long.class);

    private final DefaultRedisScript<String> verifyScript =
            new DefaultRedisScript<>(VERIFY_SCRIPT, String.class);

    public RedisOtpStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryStartCooldown(
            Long userId,
            String purpose,
            String referenceId,
            Duration cooldown) {

        Boolean acquired = redis.opsForValue()
                .setIfAbsent(cooldownKey(userId, purpose, referenceId), "1", cooldown);

        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void save(
            UUID otpId,
            Long userId,
            String purpose,
            String referenceId,
            String otpHash,
            Duration ttl) {

        redis.execute(
                saveScript,
                List.of(otpKey(otpId), activeKey(userId, purpose, referenceId)),
                String.valueOf(userId),
                purpose,
                referenceId,
                otpHash,
                String.valueOf(ttl.toMillis()),
                otpId.toString(),
                OTP_PREFIX
        );
    }

    @Override
    public VerifyResult verify(
            UUID otpId,
            String presentedHash,
            String referenceId,
            int maxAttempts) {

        String result = redis.execute(
                verifyScript,
                List.of(otpKey(otpId)),
                presentedHash,
                String.valueOf(maxAttempts),
                referenceId == null ? "" : referenceId
        );

        if (result == null) {
            return new VerifyResult(VerifyResult.Outcome.NOT_FOUND, 0);
        }

        String[] parts = result.split(":");
        int remaining = parts.length > 1
                ? Integer.parseInt(parts[1])
                : 0;

        return switch (parts[0]) {
            case "1" -> new VerifyResult(VerifyResult.Outcome.LOCKED, 0);
            case "2" -> new VerifyResult(VerifyResult.Outcome.WRONG_REFERENCE, 0);
            case "3" -> new VerifyResult(VerifyResult.Outcome.VERIFIED, 0);
            case "4" -> new VerifyResult(VerifyResult.Outcome.WRONG_OTP, remaining);
            default -> new VerifyResult(VerifyResult.Outcome.NOT_FOUND, 0);
        };
    }

    @Override
    public void discard(UUID otpId, Long userId, String purpose, String referenceId) {
        redis.delete(List.of(
                otpKey(otpId),
                activeKey(userId, purpose, referenceId),
                cooldownKey(userId, purpose, referenceId)
        ));
    }

    // ED4: Returns the remaining resend cooldown in seconds.
    @Override
    public long cooldownRemainingSeconds(
            Long userId,
            String purpose,
            String referenceId) {

        Long seconds = redis.getExpire(
                cooldownKey(userId, purpose, referenceId),
                TimeUnit.SECONDS
        );

        return seconds == null || seconds < 0 ? 0 : seconds;
    }

    // ED4: Checks whether a command was already processed.
    @Override
    public boolean isCommandHandled(String commandId) {
        return Boolean.TRUE.equals(redis.hasKey(commandKey(commandId)));
    }

    // ED4: Stores the processed command ID with a TTL.
    @Override
    public void markCommandHandled(String commandId, Duration ttl) {
        redis.opsForValue().set(commandKey(commandId), "1", ttl);
    }

    private String commandKey(String commandId) {
        return "otp-cmd:" + commandId;
    }

    private String otpKey(UUID otpId) {
        return OTP_PREFIX + otpId;
    }

    private String activeKey(Long userId, String purpose, String referenceId) {
        return "otp-active:" + userId + ":" + purpose + ":" + referenceId;
    }

    private String cooldownKey(Long userId, String purpose, String referenceId) {
        return "otp-cooldown:" + userId + ":" + purpose + ":" + referenceId;
    }
}

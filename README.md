# otp-service

> Owns the lifecycle of one-time passwords for a bank transfer: it generates the 6-digit code, stores **only a keyed hash** of it in Redis, hands the plaintext code to the notification service for e-mail delivery, and verifies a code that the user presents. Application port **8086**, management/metrics port **18086**.

- [What It Does](#what-it-does)
- [Architecture At A Glance](#architecture-at-a-glance)
- [HTTP API](#http-api)
- [Error Response Shape](#error-response-shape)
- [Database](#database)
- [Kafka Topics](#kafka-topics)
- [Configuration](#configuration)
- [Behaviour Rules](#behaviour-rules)
- [Running It](#running-it)
- [API Quick Reference (curl examples)](#api-quick-reference-curl-examples)
- [Configuration Reference (quick table)](#configuration-reference-quick-table)
- [Troubleshooting](#troubleshooting)
- [Notes and Known Limitations](#notes-and-known-limitations)

## What It Does

The OTP service owns the lifecycle of one-time passwords for a bank transfer. It generates the numeric code, stores only a keyed hash of it, hands the plaintext code to the notification service (`notification-service`, port 8087) for e-mail delivery, and verifies a code that the user presents. It also owns the resend cooldown, the wrong-attempt counter, and the "already handled command" marker that makes the Kafka flow idempotent.

The service holds **no state of its own beyond Redis**. There is no PostgreSQL schema behind it, no JPA, no JDBC — see [Database](#database). Every piece of state (the code hash, the expiry, the attempt counter, the cooldown, the command marker) lives in Redis under the `otp:` key family and disappears with its TTL.

It is reachable two ways. Synchronously, over an internal REST API (`POST /internal/otp/send`, `POST /internal/otp/verify`) that the fund-transfer service (`fund-transfer-service`, port 8082) calls. Asynchronously, over Kafka: it consumes `otp.commands` and publishes `otp.events`, which is the saga flow. The two paths share the same `OtpServiceImpl` business rules — same 6-digit code, same 5-minute expiry, same 3-attempt limit, same 30-second resend cooldown, same single-use rule — but they differ in how a failure is reported: the REST path returns an HTTP status, the Kafka path publishes an event.

It does **not** own the mail itself. It calls `POST /internal/notifications/templated` on the notification service with `{"to": …, "type": "OTP", "params": {"otp": …}}` and lets that service render and send the message, record it in `notification_logs`, and announce a delivery failure back to the saga. It does not own accounts, balances, transfers or fees.

## Architecture At A Glance

```
REST path (fund-transfer-service)
  fund-transfer-service  --POST /internal/otp/send-->  otp-service
  fund-transfer-service  --POST /internal/otp/verify-> otp-service
                                                          |
                                              Redis (state, no SQL)
                                                          |
                              POST /internal/notifications/templated
                                                          v
                                              notification-service
                                                          |
                                                   SMTP (e-mail)

Kafka path (saga)
  fund-transfer-service --IssueOtp--> otp.commands --> otp-service
  fund-transfer-service <--OtpIssued-- otp.events  <-- otp-service
  notification-service  <--OtpIssued-- otp.events
```

| Depends on | Depended on by |
|---|---|
| Redis (`127.0.0.1:6379` by default) — the only store | `fund-transfer-service` (port 8082) — `OtpClientImpl`, calls `POST /internal/otp/send` and `POST /internal/otp/verify` |
| `notification-service` (port 8087) — e-mail delivery over the internal REST API | `notification-service` (port 8087) — consumes `otp.events` and sends the OTP mail |
| Kafka (`localhost:9092`) — consumes `otp.commands`, publishes `otp.events` | Kafka `otp.events` — consumed by `notification-service` |
| Eureka (`localhost:8761`) — service discovery for the load-balanced `RestClient` | |

The api-gateway (`api-gateway`, port 8080) routes `Path=/internal/**` to `uri: no://op` with the id `blocked-internal`, so these endpoints are **not** reachable from outside the service mesh or from a browser.

## HTTP API

Base path: `/internal/otp`. Both endpoints are `POST` and accept `application/json`. Neither requires authentication — the api-gateway blocks `/internal/**` instead.

### `POST /internal/otp/send`

Generates a code, stores it in Redis and hands it to the notification service. Returns **200** on success.

Request body — `SendOtpRequest`:

| Field | Type | Required | Validation message |
|---|---|---|---|
| `userId` | `Long` | yes (`@NotNull`) | `"userId is required"` |
| `email` | `String` | yes (`@NotBlank`) | `"email is required"` |
| `email` | `String` | (`@Email`) | `"email format is invalid"` |
| `purpose` | `String` | yes (`@NotBlank`) | `"purpose is required"` |
| `purpose` | `String` | (`@Size(max = 50)`) | `"purpose must be at most 50 characters"` |
| `referenceId` | `String` | optional (`@Size(max = 100)`) | `"referenceId must be at most 100 characters"` |

`purpose` is upper-cased and trimmed before it is used as part of a Redis key; `referenceId` is trimmed and becomes the empty string when it is null.

Response body — `SendOtpResponse`:

| Field | Type | Meaning |
|---|---|---|
| `otpId` | `String` | UUID of the stored code. Pass it to `/internal/otp/verify`. |
| `message` | `String` | Always `"OTP sent to your email"`. |

Example response:

```json
{"otpId":"8f3a1c2d-9b4e-4a71-8c0d-2f5e6b7a1d93","message":"OTP sent to your email"}
```

Status codes:

| Status | When | `message` |
|---|---|---|
| **200** | Code generated, stored and handed to the notification service | — |
| **400** | Bean validation failure | `"<field>: <message>; <field>: <message>"` — the individual messages joined with `"; "` |
| **400** | Malformed JSON body | `"Malformed request body"` |
| **404** | Unknown path | `"Not found"` |
| **429** | Resend cooldown still running | `"Please wait 30 seconds before requesting a new OTP"` (the number is `app.otp.resend-cooldown-seconds`) |
| **500** | Anything else | `"Something went wrong"` (stack trace logged as `"Unhandled error in the OTP service"`) |
| **502** | The notification service could not be reached | `"Could not send the OTP email, try again"` |
| **503** | Redis/storage problem | `"OTP storage is unavailable, please try again"` |

### `POST /internal/otp/verify`

Verifies a code. Returns **HTTP 200 in every case**, including a wrong code — the outcome is in the body, not the status code.

Request body — `VerifyOtpRequest`:

| Field | Type | Required | Validation message |
|---|---|---|---|
| `otpId` | `String` | yes (`@NotBlank`) | `"otpId is required"` |
| `otp` | `String` | yes (`@NotBlank`) | `"otp is required"` |
| `otp` | `String` | (`@Pattern(regexp = "\\d{6}")`) | `"otp must be exactly 6 digits"` |
| `referenceId` | `String` | optional (`@Size(max = 100)`) | `"referenceId must be at most 100 characters"` |

Response body — `VerifyOtpResponse`:

| Field | Type | Meaning |
|---|---|---|
| `verified` | `boolean` | `true` only when the code was correct. |
| `message` | `String` | Human-readable outcome (exact strings below). |

| Outcome | `verified` | `message` |
|---|---|---|
| Correct code | `true` | `"OTP verified"` |
| `otpId` is not a UUID | `false` | `"Invalid OTP"` |
| Key gone / expired | `false` | `"OTP expired or invalid. Request a new one"` |
| Locked (too many wrong attempts) | `false` | `"Too many wrong attempts. Request a new OTP"` |
| `referenceId` does not match the stored one | `false` | `"OTP does not belong to this request"` |
| Wrong code, attempts left | `false` | `"Invalid OTP. " + remainingAttempts + " attempt(s) remaining"` |
| Wrong code, no attempts left | `false` | `"Too many wrong attempts. Request a new OTP"` |

Status codes: **200** for all of the outcomes above; **400** on validation failure or a malformed body (`"Malformed request body"`); **404** unknown path (`"Not found"`); **500** unexpected (`"Something went wrong"`); **503** if Redis fails (`"OTP storage is unavailable, please try again"`).

### Error Response Shape

Every non-2xx response uses the same body — `ErrorResponse`:

| Field | Type | Notes |
|---|---|---|
| `timestamp` | `LocalDateTime` | Server-local time of the failure. Serialised as `2026-11-02T10:15:30.123456`. |
| `status` | `int` | The HTTP status code, repeated in the body. |
| `error` | `String` | The HTTP reason phrase, e.g. `"Bad Request"`, `"Too Many Requests"`, `"Bad Gateway"`, `"Service Unavailable"`. |
| `message` | `String` | The exact human-readable explanation (see the tables above). |

Example:

```json
{
  "timestamp": "2026-11-02T10:15:30.123456",
  "status": 429,
  "error": "Too Many Requests",
  "message": "Please wait 30 seconds before requesting a new OTP"
}
```

## Database

**otp-service has NO database.** This is a property of the code, not a deployment choice.

* `pom.xml` contains no `spring-boot-starter-data-jpa`, no `spring-boot-starter-jdbc` and no `postgresql` driver. Its dependencies are `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-loadbalancer`, `spring-boot-starter-test`, `spring-boot-starter-data-redis`, `spring-boot-starter-actuator`, `micrometer-registry-prometheus` and `spring-kafka`.
* `application.yaml` has no `spring.datasource` block — only `spring.data.redis` and `spring.kafka`.
* There is no `@Entity`, no `JpaRepository` and no `DataSource` anywhere under `src/`.
* There is no `schema.sql` and no `data.sql`.
* `run-all.sh` lists the service as `"otp-service|8086|"` — the third field (the database) is empty, so it is started **without** `SPRING_DATASOURCE_URL`.

### `otp_db` / `otp_records` are a legacy artefact — do not use them

The root `postgres/init/01-create-databases.sql` still contains `CREATE DATABASE otp_db;`. **That database is never used by this service.** It is a leftover from an earlier JPA implementation.

The evidence is in this repository's own history. Commit `4efc26e` contained:

* `src/main/java/com/banking/otp/entity/OtpRecord.java` — `@Entity @Table(name = "otp_records")` with columns `id` (`UUID`), `userId`, `purpose`, `referenceId`, `otpHash`, `expiresAt`, `attempts`, `used`, `created_at`, and an index `idx_otp_lookup` on `(userId, purpose, referenceId)`.
* `src/main/java/com/banking/otp/repository/OtpRecordRepository.java` — a `JpaRepository<OtpRecord, UUID>` with `findByIdForUpdate` (pessimistic write lock), `findTopByUserIdAndPurposeAndReferenceIdOrderByCreatedAtDesc` and `invalidateActive`.

Commit `4a12223` ("Otp store using redis database and using hashing") **deleted both files** and replaced them with `RedisOtpStore`. The `otp_records` table therefore exists in neither the schema nor the code. If you find a row in `otp_records` it is stale data from an old deployment; the running service will never read it.

**All live state is in Redis.** The key formats are:

| Key | Type | Contents | TTL |
|---|---|---|---|
| `otp:<otpId>` | Hash | `user`, `purpose`, `ref`, `hash`, `attempts` | `app.otp.expiry-minutes` (default **5 min**) via `PEXPIRE` |
| `otp-active:<userId>:<purpose>:<referenceId>` | String | the current `otpId` | same as above (set with `PX`) |
| `otp-cooldown:<userId>:<purpose>:<referenceId>` | String | `"1"` | `app.otp.resend-cooldown-seconds` (default **30 s**), set with `SETNX` |
| `otp-cmd:<commandId>` | String | `"1"` | `Duration.ofHours(1)` (constant `COMMAND_MEMORY` in `IssueOtpHandler`) |

Hash fields of `otp:<otpId>`:

| Field | Type | Value |
|---|---|---|
| `user` | String | the `userId` as a string |
| `purpose` | String | the upper-cased purpose |
| `ref` | String | the `referenceId` |
| `hash` | String | the keyed hash of the code, lowercase hex |
| `attempts` | String | number of wrong attempts, starts at `"0"` |

### How the code is protected

* **Generation** — `OtpGenerator` uses `SecureRandom` and `String.format("%06d", random.nextInt(1_000_000))`, so the code is **exactly 6 digits, zero-padded** (`000042` is a possible code).
* **Hashing** — `OtpHasher` computes `HmacSHA256` with the key `app.otp.hash-secret` (UTF-8 bytes, `SecretKeySpec`) over the message `otpId + ":" + otp`, and stores the result as lowercase hex via `HexFormat.of().formatHex(...)`. **The plaintext OTP is never stored** — only this hash. `OtpHasher.matches(...)` exists but is not on the store path; the comparison happens inside the Redis Lua script.
* **Encryption of the published payload** — `EventCrypto` uses `AES/GCM/NoPadding` with a 12-byte IV (`IV_BYTES = 12`) and a 128-bit tag (`TAG_BITS = 128`). The output is `Base64( iv + ciphertext + tag )`. The key is the Base64-decoded `app.messaging.crypto-key` and **must decode to exactly 32 bytes**, otherwise startup fails with `IllegalStateException("EVENT_CRYPTO_KEY must be 32 bytes (use: openssl rand -base64 32)")`; invalid Base64 fails with `IllegalStateException("EVENT_CRYPTO_KEY is not valid Base64")`.
* **The AAD is the `transferId`** — `IssueOtpHandler` calls `crypto.encrypt(otp, transferId)`, and the notification service decrypts with `crypto.decrypt(payload.encryptedCode(), payload.transferId().trim())`. The code therefore cannot be decrypted for any other transfer.
* `EventCrypto.fingerprint()` is `Integer.toHexString(Arrays.hashCode(key.getEncoded()))`. It is logged at startup by `EventCryptoFingerprintLogger` as `"Event crypto key fingerprint: {}"` so that otp-service and notification-service can be compared without printing the key.

### The Redis Lua scripts

`SAVE_SCRIPT` (atomic):

```
local old = redis.call('GET', KEYS[2])
if old then redis.call('DEL', ARGV[7] .. old) end
redis.call('HSET', KEYS[1], 'user', ARGV[1], 'purpose', ARGV[2], 'ref', ARGV[3], 'hash', ARGV[4], 'attempts', '0')
redis.call('PEXPIRE', KEYS[1], ARGV[5])
redis.call('SET', KEYS[2], ARGV[6], 'PX', ARGV[5])
return 1
```

Saving a new code **deletes the previous code** for the same user + purpose + reference.

`VERIFY_SCRIPT` (atomic), returns `"code:remaining"`:

```
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
```

| Code | Meaning | Effect |
|---|---|---|
| `0` | not found | key gone or expired |
| `1` | locked | attempt limit reached |
| `2` | wrong reference | `referenceId` does not match |
| `3` | verified | the key is **deleted** — a code works only once |
| `4` | wrong OTP | attempt counter incremented |

## Kafka Topics

Topics are declared in `TopicNames`: `OTP_COMMANDS = "otp.commands"` (consumed), `OTP_EVENTS = "otp.events"` (published), `DLT_SUFFIX = ".DLT"`.

Every message on the bus uses the same envelope — `EventEnvelope`:

| Field | Type | Meaning |
|---|---|---|
| `eventId` | `String` | Fresh UUID for the message. |
| `eventType` | `String` | e.g. `"IssueOtp"`, `"OtpIssued"`. |
| `version` | `int` | Always `1`. |
| `correlationId` | `String` | The `transferId`. |
| `causationId` | `String` | The `eventId` of the message that caused this one. |
| `occurredAt` | `Instant` | When the message was built. |
| `producer` | `String` | `app.messaging.producer-name`, i.e. `"otp-service"`. |
| `payload` | `JsonNode` | The type-specific payload. |

### Consumed: `otp.commands`

Listener: `OtpCommandsConsumer`, `@KafkaListener(topics = TopicNames.OTP_COMMANDS)`. Only `eventType == "IssueOtp"` is handled; any other type is ignored with the debug line `"Ignoring unknown command type {}"`.

Command type **`IssueOtp`** — payload `IssueOtpPayload`:

| Field | Type | Meaning |
|---|---|---|
| `transferId` | `String` | The transfer the code belongs to. Also the Kafka key. |
| `userId` | `Long` | The user the code is for. |
| `email` | `String` | Where the code is delivered. |
| `purpose` | `String` | Upper-cased and trimmed before use. |

Validation in `IssueOtpHandler.validate(...)`. Each failure is an `InvalidEventException`, which is **not retryable** and goes straight to the DLT:

| Condition | Message |
|---|---|
| `transferId` blank or `length() > 100` | `"IssueOtp: transferId is missing or too long"` |
| `userId == null` | `"IssueOtp: userId is missing"` |
| `email` blank or not matching `^[^@\s]+@[^@\s]+\.[^@\s]+$` | `"IssueOtp: email is missing or invalid"` |
| `purpose` blank or `length() > 50` | `"IssueOtp: purpose is missing or too long"` |

### Published: `otp.events`

Publisher: `DirectEventPublisher`. The Kafka key is the `transferId`, `version` is `1`, `eventId` is a fresh `UUID.randomUUID()`, `correlationId` is the transferId and `causationId` is the command's `eventId`. The publish waits up to `PUBLISH_TIMEOUT_SECONDS = 30`.

| Event type | Payload record | Published when |
|---|---|---|
| `OtpIssued` | `OtpIssuedPayload` | A code was generated and stored successfully. |
| `OtpIssueRejected` | `OtpIssueRejectedPayload` | The resend cooldown was still running. |

**`OtpIssued`** — `OtpIssuedPayload`:

| Field | Type | Meaning |
|---|---|---|
| `transferId` | `String` | The transfer the code belongs to. |
| `otpId` | `String` | The UUID of the stored code. |
| `email` | `String` | The trimmed recipient address. |
| `encryptedCode` | `String` | `crypto.encrypt(otp, transferId)` — Base64 AES-GCM, AAD = `transferId`. |
| `expiresAt` | `Instant` | `Instant.now().plus(ttl)` where `ttl` = `app.otp.expiry-minutes` minutes. |

**`OtpIssueRejected`** — `OtpIssueRejectedPayload`:

| Field | Type | Meaning |
|---|---|---|
| `transferId` | `String` | The transfer the command was for. |
| `reasonCode` | `String` | The literal string **`"COOLDOWN"`** — the only rejection reason produced. |
| `retryAfterSeconds` | `long` | Seconds left on the resend cooldown. |

### Retries, dead letters and metrics

`KafkaConsumerConfig` uses a `DeadLetterPublishingRecoverer` that parks a failed record on `<topic>.DLT` on the same partition, with `ExponentialBackOffWithMaxRetries(4)` (initial interval 1 s, multiplier 2.0, max interval 10 s). `InvalidEventException` and `EventCryptoException` are registered as **not retryable**, so they go straight to `otp.commands.DLT`.

Metric: `otp.issued` counter with the tag `result` ∈ {`duplicate`, `rejected`, `issued`, `publish-failed`}.

Topic retention (from the event-bus catalogue): `otp.events` is kept for **2 hours** with a **4 hour** DLT retention — deliberately longer than the 5-minute OTP lifetime, so a slow notification service does not lose the code. `otp.commands` is kept for 7 days with a 14-day DLT. Every topic has **3 partitions**.

## Configuration

### Server and Spring properties

| Name | Default | Meaning |
|---|---|---|
| `server.port` | `8086` | Application port. Overridden by `SERVER_PORT`. |
| `management.server.port` | `18086` | Actuator / Prometheus port. |
| `spring.application.name` | `otp-service` | Name registered with Eureka. |
| `spring.data.redis.host` | `${REDIS_HOST:127.0.0.1}` | Redis host. |
| `spring.data.redis.port` | `${REDIS_PORT:6379}` | Redis port. |
| `spring.data.redis.password` | `${REDIS_PASSWORD:redis2188}` | Redis password. |
| `spring.data.redis.timeout` | `2s` | Command timeout. |
| `spring.data.redis.connect-timeout` | `2s` | Connect timeout. |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}` | Broker list. |
| `spring.kafka.consumer.group-id` | `otp-service` | Consumer group. |
| `spring.kafka.consumer.auto-offset-reset` | `earliest` | Where a new group starts. |
| `spring.kafka.consumer.enable-auto-commit` | `false` | Offsets are committed by the listener. |
| `spring.kafka.listener.ack-mode` | `record` | Ack per record. |
| `spring.kafka.listener.concurrency` | `3` | Listener threads. |
| `spring.kafka.producer.acks` | `all` | Full durability. |
| `spring.kafka.producer.properties.enable.idempotence` | `true` | Idempotent producer. |
| `spring.kafka.producer.properties.linger.ms` | `0` | Send immediately. |
| `spring.kafka.producer.properties.max.block.ms` | `15000` | Max block on send. |
| `spring.kafka.producer.properties.request.timeout.ms` | `5000` | Request timeout. |
| `spring.kafka.producer.properties.delivery.timeout.ms` | `10000` | Delivery timeout. |
| `spring.config.import` | `optional:file:.env[.properties]` | Reads a `.env` file from the working directory if present. |
| `eureka.client.service-url.defaultZone` | `${EUREKA_URL:http://localhost:8761/eureka}` | Discovery server. |
| `eureka.instance.prefer-ip-address` | `true` | Register by IP. |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Exposed actuator endpoints. |
| `management.metrics.distribution.percentiles-histogram.http.server.requests` | `true` | Latency histograms. |

### Custom `app.*` properties

| Name | Default | Meaning |
|---|---|---|
| `app.otp.expiry-minutes` | `5` | Lifetime of the OTP in Redis; also the `expiresAt` on `OtpIssued`. |
| `app.otp.max-attempts` | `3` | Maximum number of wrong verification attempts before the code is locked. |
| `app.otp.resend-cooldown-seconds` | `30` | Minimum seconds between two OTPs for the same user + purpose + reference. |
| `app.otp.hash-secret` | `${OTP_HASH_SECRET:ba9993fb48999f6b4b9e249235ed108f9864de5c905f53b91b9294cb3eab5b08}` | HMAC-SHA256 key for hashing the code. |
| `app.otp.log-dev-otp` | `${OTP_LOG_DEV:false}` | When `true`, the plaintext code is logged at WARN: `"DEV ONLY - OTP for otpId {} is {}"` (REST path) / `"DEV ONLY - OTP for transfer {} (otpId {}) is {}"` (Kafka path). |
| `app.messaging.producer-name` | `otp-service` | The `producer` field of the published envelope. |
| `app.messaging.crypto-key` | `${EVENT_CRYPTO_KEY:pxUXlFhqre/31HUOw+mT9Oz80+rJz33fRtXx/kcGeTk=}` | Base64 AES-256 key for `EventCrypto`; must be 32 bytes. |
| `app.notification.base-url` | `http://notification-service` | Base URL of the notification service for the load-balanced `RestClient`. Connect timeout 3000 ms, read timeout 10000 ms. |

Binding classes: `OtpProperties` (`@ConfigurationProperties(prefix = "app.otp")`, record `expiryMinutes`, `maxAttempts`, `resendCooldownSeconds`, `hashSecret`, `logDevOtp`) and `MessagingProperties` (`@ConfigurationProperties(prefix = "app.messaging")`, record `producerName`, `cryptoKey`).

### Environment variables

| Name | Default | Used for |
|---|---|---|
| `REDIS_HOST` | `127.0.0.1` | `spring.data.redis.host` |
| `REDIS_PORT` | `6379` | `spring.data.redis.port` |
| `REDIS_PASSWORD` | `redis2188` | `spring.data.redis.password` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | `spring.kafka.bootstrap-servers` |
| `EUREKA_URL` | `http://localhost:8761/eureka` | `eureka.client.service-url.defaultZone` |
| `OTP_HASH_SECRET` | `ba9993fb48999f6b4b9e249235ed108f9864de5c905f53b91b9294cb3eab5b08` | `app.otp.hash-secret` |
| `OTP_LOG_DEV` | `false` | `app.otp.log-dev-otp` |
| `EVENT_CRYPTO_KEY` | `pxUXlFhqre/31HUOw+mT9Oz80+rJz33fRtXx/kcGeTk=` | `app.messaging.crypto-key` |
| `SERVER_PORT` | `8086` (set by `run-all.sh`) | Overrides `server.port`. |

#### Environment variables you must set

Every variable above has a working default, so the service starts with none of them set. Two of them are nevertheless **must match** across services:

* `EVENT_CRYPTO_KEY` must be **identical** in `otp-service` and `notification-service`. If the two differ, the notification service cannot decrypt the code, the mail is never sent and the message ends up in `otp.events.DLT`. Compare the `"Event crypto key fingerprint: {}"` line in the two logs to check.
* `REDIS_PASSWORD` must match the Redis instance.

The key itself must be Base64 for exactly 32 bytes:

```bash
openssl rand -base64 32
```

## Behaviour Rules

| Rule | Value | Notes |
|---|---|---|
| Code length | exactly **6 digits** | Zero-padded, so `000042` is possible. The verify request enforces `@Pattern(regexp = "\\d{6}")`. |
| Expiry | **5 minutes** (`app.otp.expiry-minutes`) | The Redis hash key and the active-pointer key both carry the TTL. On expiry the key is gone and verify returns `"OTP expired or invalid. Request a new one"`. |
| Attempt limit | **3** (`app.otp.max-attempts`) | The counter is the Redis hash field `attempts`. At `attempts >= 3` the code is locked: `"Too many wrong attempts. Request a new OTP"`. A wrong attempt that reaches the limit returns that same message rather than the remaining-attempts message. |
| Resend cooldown | **30 s** (`app.otp.resend-cooldown-seconds`) | Keyed on user + purpose + reference. On the REST path a request inside the cooldown returns **429** `"Please wait 30 seconds before requesting a new OTP"`. On the Kafka path the same cooldown produces an `OtpIssueRejected` event with `reasonCode = "COOLDOWN"` and `retryAfterSeconds` = the remaining seconds — not an error. |
| Single use | one verification only | A correct code deletes the Redis key immediately (`VERIFY_SCRIPT` returns `3:0` after `DEL`), so the same code cannot be used twice. |
| Reference binding | `referenceId` must match | If a `referenceId` is supplied at verify time it must equal the stored `ref`, otherwise `"OTP does not belong to this request"`. |
| New code replaces the old | automatic | `SAVE_SCRIPT` deletes the previously active code for the same user + purpose + reference. |
| Failed delivery cleanup | automatic | If the notification service cannot be reached, `OtpServiceImpl` calls `store.discard(otpId, userId, purpose, referenceId)`, which deletes `otp:<otpId>`, `otp-active:...` and `otp-cooldown:...`, so the cooldown does not block a retry. The original `NotificationUnavailableException` is rethrown. A Redis failure during this cleanup is only logged (`"Could not remove the OTP {} after a failed delivery: {}"`). |
| HTTP delivery retries | 3 attempts | `NotificationClientImpl` posts to `/internal/notifications/templated` with `{"to": email, "type": "OTP", "params": {"otp": otp}}`, up to `MAX_ATTEMPTS = 3` attempts, waiting `RETRY_WAIT_MILLIS * attempt` (1000 ms, 2000 ms). Retryable: 5xx `HttpStatusCodeException`, `IllegalArgumentException` / `IllegalStateException` (the load balancer could not resolve the service), `ResourceAccessException` (connection refused or timeout). **Not** retryable: 4xx responses — the request itself is wrong, so it fails immediately with `"Could not send the OTP email, try again"`. Every attempt carries the **same** code, so a duplicate mail shows the same number. |
| Kafka command idempotency | 1 hour | `otp-cmd:<commandId>` in Redis with a 1 h TTL (`COMMAND_MEMORY` in `IssueOtpHandler`). A duplicate `IssueOtp` command is skipped and counted as `duplicate`. |
| Publish failure rollback | automatic | If publishing `OtpIssued` fails, the stored OTP is discarded and the exception is rethrown so the Kafka command is retried. The cooldown and the command marker are only written **after** a successful publish. |

### Error codes

This service produces **no** string error codes. Everything is an HTTP status plus the `ErrorResponse` body:

| Status | Meaning |
|---|---|
| `400` | Validation failure or malformed JSON |
| `404` | Unknown path |
| `429` | Resend cooldown |
| `500` | Unexpected error |
| `502` | Notification service unreachable |
| `503` | Redis unavailable |

The error code `OTP_DELIVERY_FAILED` does **not** live here — it lives in `fund-transfer-service` (`TransferSagaEngine.onOtpDeliveryFailed`) and is triggered by the `OtpDeliveryFailed` event that the notification service publishes.

## Running It

### Prerequisites

* Java 17
* Maven 3.8+ (or use the bundled `./mvnw`)
* Redis at `127.0.0.1:6379` (password `REDIS_PASSWORD`)
* Kafka at `localhost:9092`
* Eureka at `localhost:8761`
* The notification service at `http://notification-service` (resolved through Eureka) — or `http://localhost:8087` with the `local` profile
* **No database.** Do not configure a datasource.

### Build

```bash
cd otp-service
mvn -q clean package -DskipTests
# or
./mvnw -q clean package -DskipTests
```

Test: `mvn test` (only `OtpServiceApplicationTests#contextLoads` exists).
Jar: `otp-service/target/otp-service-0.0.1-SNAPSHOT.jar`.

### Run

```bash
cd otp-service
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export REDIS_PASSWORD='redis2188'
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export EUREKA_URL=http://localhost:8761/eureka
export OTP_HASH_SECRET='ba9993fb48999f6b4b9e249235ed108f9864de5c905f53b91b9294cb3eab5b08'
export OTP_LOG_DEV=false
export EVENT_CRYPTO_KEY='pxUXlFhqre/31HUOw+mT9Oz80+rJz33fRtXx/kcGeTk='
export SERVER_PORT=8086

java -jar target/otp-service-0.0.1-SNAPSHOT.jar
```

To run without Eureka, start with the `local` profile, which pins the notification service to `http://localhost:8087`:

```bash
java -jar target/otp-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

### Whole stack

From the repository root:

```bash
./run-all.sh          # start everything
./run-all.sh --build  # rebuild the jars first
./stop-all.sh         # stop everything
```

`run-all.sh` requires `run.env` to define `DB_PASSWORD`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `REDIS_PASSWORD` and `EVENT_CRYPTO_KEY`. It starts `otp-service` on port **8086** with **no** `SPRING_DATASOURCE_*` variables.

### Check that it is healthy

```bash
curl -s http://localhost:18086/actuator/health
```

```json
{"status":"UP"}
```

Prometheus metrics are on the same port at `/actuator/prometheus`.

### Stop it

```bash
# foreground
Ctrl+C

# as a background process
kill <pid>
# or, for the whole stack
./stop-all.sh
```

## API Quick Reference (curl examples)

The `/internal/**` endpoints are blocked by the api-gateway, so call them directly on port 8086. No `Authorization` header is required — the endpoints do not authenticate.

Send an OTP:

```bash
curl -i -X POST http://localhost:8086/internal/otp/send \
  -H 'Content-Type: application/json' \
  -d '{
        "userId": 42,
        "email": "customer@example.com",
        "purpose": "TRANSFER",
        "referenceId": "TXN-3A422C5733554406"
      }'
```

```http
HTTP/1.1 200
{"otpId":"8f3a1c2d-9b4e-4a71-8c0d-2f5e6b7a1d93","message":"OTP sent to your email"}
```

Verify the code:

```bash
curl -i -X POST http://localhost:8086/internal/otp/verify \
  -H 'Content-Type: application/json' \
  -d '{
        "otpId": "8f3a1c2d-9b4e-4a71-8c0d-2f5e6b7a1d93",
        "otp": "123456",
        "referenceId": "TXN-3A422C5733554406"
      }'
```

```http
HTTP/1.1 200
{"verified":true,"message":"OTP verified"}
```

A wrong code still returns HTTP 200:

```json
{"verified":false,"message":"Invalid OTP. 2 attempt(s) remaining"}
```

A second request inside the resend cooldown returns HTTP 429:

```bash
curl -i -X POST http://localhost:8086/internal/otp/send \
  -H 'Content-Type: application/json' \
  -d '{"userId":42,"email":"customer@example.com","purpose":"TRANSFER","referenceId":"TXN-3A422C5733554406"}'
```

```http
HTTP/1.1 429
{"timestamp":"2026-11-02T10:15:30.123456","status":429,"error":"Too Many Requests","message":"Please wait 30 seconds before requesting a new OTP"}
```

An unknown path returns HTTP 404:

```bash
curl -i http://localhost:8086/internal/otp/nope
```

## Configuration Reference (quick table)

| Knob | Default | Change it to |
|---|---|---|
| `app.otp.expiry-minutes` | `5` | Any integer number of minutes; sets the Redis TTL and `expiresAt`. |
| `app.otp.max-attempts` | `3` | Any integer; the number of wrong tries before the code locks. |
| `app.otp.resend-cooldown-seconds` | `30` | Any integer; also the number in the HTTP 429 message. |
| `app.otp.hash-secret` | a built-in default | A long random string; changing it invalidates every stored code. |
| `app.otp.log-dev-otp` | `false` | `true` in development to see the code in the log. |
| `app.messaging.crypto-key` | a built-in default | Base64 of 32 bytes; **must match notification-service**. |
| `app.messaging.producer-name` | `otp-service` | The `producer` field of published envelopes. |
| `app.notification.base-url` | `http://notification-service` | The notification service URL; use `http://localhost:8087` for local runs. |
| `server.port` | `8086` | The application port. |
| `management.server.port` | `18086` | The actuator/metrics port. |
| `spring.data.redis.host` / `.port` / `.password` | `127.0.0.1` / `6379` / `redis2188` | Your Redis instance. |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `429` with `"Please wait 30 seconds before requesting a new OTP"` | The resend cooldown for this user + purpose + reference is still running. | Wait 30 seconds, or use a different `referenceId`. |
| `"OTP storage is unavailable, please try again"` (503) | Redis is unreachable or the password is wrong. | Check `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` and that Redis is running. |
| `"Could not send the OTP email, try again"` (502) | The notification service could not be reached, or it answered 4xx. | Check that `notification-service` is up and registered in Eureka; check the `notification-service` log for a 4xx from the templated endpoint. |
| `"No instances available for notification-service"` in the log | Eureka has not registered the notification service yet, or the `local` profile is not active. | Start `notification-service` first, or run with `--spring.profiles.active=local`. |
| The mail arrives but the code is rejected with `"Invalid OTP"` | The code was regenerated (a new code replaces the old one) or the mail is from an earlier request. | Use the most recent code; check `otp.issued{result="issued"}` on port 18086. |
| `"Too many wrong attempts. Request a new OTP"` | The attempt counter reached `app.otp.max-attempts`. | Request a new OTP (after the 30-second cooldown). |
| `"OTP expired or invalid. Request a new one"` | More than 5 minutes passed, or the key was deleted because the code was already used. | Request a new OTP. |
| `"OTP does not belong to this request"` | The `referenceId` sent at verify time differs from the one sent at send time. | Send the same `referenceId` in both calls. |
| Startup fails with `"EVENT_CRYPTO_KEY must be 32 bytes (use: openssl rand -base64 32)"` | `EVENT_CRYPTO_KEY` is not Base64 for 32 bytes. | `export EVENT_CRYPTO_KEY=$(openssl rand -base64 32)`. |
| Startup fails with `"EVENT_CRYPTO_KEY is not valid Base64"` | The value is not valid Base64. | Use `openssl rand -base64 32` to generate a valid key. |
| The OTP mail is never sent and the message sits in `otp.events.DLT` | The crypto keys of the two services differ, so the notification service cannot decrypt the code. | Compare the `"Event crypto key fingerprint: {}"` line in both logs; set the same `EVENT_CRYPTO_KEY` in both. |
| `500` with `"Something went wrong"` | An unexpected error; the stack trace is logged as `"Unhandled error in the OTP service"`. | Read the otp-service log for the stack trace. |
| `404` with `"Not found"` | The path does not exist (for example a typo in `/internal/otp/send`). | Check the path; the only endpoints are `/internal/otp/send` and `/internal/otp/verify`. |
| The service starts but is not in Eureka | `EUREKA_URL` points somewhere else, or the discovery service is down. | Check `EUREKA_URL` and the discovery service on port 8761. |

## Notes and Known Limitations

* **The `otp_db` database and the `otp_records` table are a legacy artefact.** They were created by an earlier JPA implementation (`OtpRecord`, `OtpRecordRepository`, both deleted in commit `4a12223`) and are **not** used by the running service. `postgres/init/01-create-databases.sql` still contains `CREATE DATABASE otp_db;` — the database exists, but nothing in this service reads or writes it. All live state is in Redis.
* **The plaintext code lives in three places**: in the e-mail body, in the request body of the REST call to the notification service, and — for a very short time — in the local variable of the handler. It is never written to a database and never written to a log unless `app.otp.log-dev-otp` is `true`.
* **`app.otp.log-dev-otp` is a development switch.** Turn it off in any shared environment: it prints the code at WARN level (`"DEV ONLY - OTP for otpId {} is {}"`). The repository's `run.env` sets `OTP_LOG_DEV=true`.
* **The built-in defaults are development defaults.** `OTP_HASH_SECRET`, `EVENT_CRYPTO_KEY` and `REDIS_PASSWORD` all ship with a value in `application.yaml`. Rotate them before any shared deployment.
* **`EVENT_CRYPTO_KEY` has to be the same string in otp-service and notification-service.** This is not enforced anywhere; it only works because the same value happens to be set in both. The fingerprint log line exists to make a mismatch visible.
* **The REST path and the Kafka path both deliver the mail through the notification service**, but only the REST path retries the delivery itself (3 attempts). The Kafka path relies on Kafka's retry and DLT policy instead.
* **A retry of the HTTP delivery can send the mail twice**, but always with the same code — the user may see the number twice, never the wrong one.
* **The `local` profile is a development convenience only.** `application-local.yml` pins the notification service to `http://localhost:8087`; do not activate it in a deployed environment.
* **The service exposes `health,info,prometheus` on port 18086** and no authentication on either. Keep that port internal.
* **`OtpHasher.matches(...)` is dead code** on the store path — the comparison is done inside the Redis Lua script, not in Java.
* **The `otp.events` topic keeps messages for 2 hours**, deliberately longer than the 5-minute OTP lifetime so that a notification service outage does not lose the code.
# otp-service

**Author:** [Jahid Hasan](https://github.com/Md-Jahid-Hasan-10)

**Project:** Banking Microservices — OTP Service

**Responsibilities:**
- OTP generation and verification
- Redis storage with keyed hashing
- Kafka command and event handling
- OTP expiry and retry protection

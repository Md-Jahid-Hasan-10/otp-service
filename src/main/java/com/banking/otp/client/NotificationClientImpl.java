package com.banking.otp.client;

import com.banking.otp.exception.NotificationUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class NotificationClientImpl implements NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClientImpl.class);

    /**
     * The email is sent over plain HTTP, so a single hiccup (SMTP timeout, a
     * restarting service) would lose the code without a trace. The saga flow
     * gets its retries from Kafka; this path has to retry on its own.
     */
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_WAIT_MILLIS = 1_000L;

    private final RestClient restClient;

    public NotificationClientImpl(@Qualifier("notificationRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void sendOtp(String email, String otp) {
        Map<String, Object> body = Map.of(
                "to", email,
                "type", "OTP",
                "params", Map.of("otp", otp)
        );

        NotificationUnavailableException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                restClient.post()
                        .uri("/internal/notifications/templated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                return;
            } catch (RuntimeException ex) {
                // The load balancer throws IllegalArgumentException when it cannot
                // resolve the service ("Service Instance cannot be null"), and
                // IllegalStateException when there is no instance at all. Both
                // happen while Eureka catches up after a restart. That is a normal
                // outage and must not end up as an unexplained HTTP 500.
                if (!isWorthRetrying(ex)) {
                    // The mail may already have been sent, so a second attempt could
                    // send it twice. Failing here is the safer choice.
                    log.error("Could not hand the OTP email to the notification service: {}",
                            ex.getMessage());
                    throw new NotificationUnavailableException("Could not send the OTP email, try again");
                }

                last = new NotificationUnavailableException("Could not send the OTP email, try again");
                log.warn("Could not hand the OTP email to the notification service "
                                + "(attempt {} of {}): {}",
                        attempt, MAX_ATTEMPTS, ex.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_WAIT_MILLIS * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new NotificationUnavailableException("Could not send the OTP email, try again");
                    }
                }
            }
        }

        log.error("Could not hand the OTP email to the notification service after {} attempts",
                MAX_ATTEMPTS);
        throw last == null
                ? new NotificationUnavailableException("Could not send the OTP email, try again")
                : last;
    }

    /**
     * A 4xx means the request itself is wrong, so it would fail the same way
     * again. Every other failure is worth another attempt.
     *
     * A second attempt cannot send a different code: the same code is in every
     * attempt, so a mail that did go out is only sent again with the same
     * number. The user may see the number twice, but never the wrong one.
     */
    private boolean isWorthRetrying(RuntimeException ex) {
        if (ex instanceof HttpStatusCodeException statusError) {
            return statusError.getStatusCode().is5xxServerError();
        }
        // The service could not be resolved at all. The load balancer reports
        // that as IllegalArgumentException or IllegalStateException.
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return true;
        }
        // Connection refused, timeout, or a broken connection: the service was
        // not reached, or the answer was lost.
        return ex instanceof ResourceAccessException;
    }
}

package com.banking.otp.client;

import com.banking.otp.exception.NotificationUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class NotificationClientImpl implements NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClientImpl.class);

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

        try {
            restClient.post()
                    .uri("/internal/notifications/templated")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            log.error("Notification service call failed: {}", ex.getMessage());
            throw new NotificationUnavailableException("Could not send the OTP email, try again");
        }
    }
}
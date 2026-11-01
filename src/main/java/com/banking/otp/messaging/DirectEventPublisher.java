package com.banking.otp.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class DirectEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final MessagingProperties properties;

    public DirectEventPublisher(KafkaTemplate<String, String> kafka,
                                ObjectMapper objectMapper,
                                MessagingProperties properties) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publish(String topic, String key, String eventType, String correlationId,
                        String causationId, Object payload) {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID().toString(), eventType, 1, correlationId, causationId,
                Instant.now(), properties.producerName(), objectMapper.valueToTree(payload));

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize event " + eventType, ex);
        }

        try {
            kafka.send(topic, key, json).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("Interrupted while publishing " + eventType, ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new EventPublishException("Could not publish " + eventType + " to " + topic, ex);
        }
    }
}
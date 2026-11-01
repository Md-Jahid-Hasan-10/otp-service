package com.banking.otp.messaging.consumer;

import com.banking.otp.messaging.EventEnvelope;
import com.banking.otp.messaging.EventParser;
import com.banking.otp.messaging.IssueOtpHandler;
import com.banking.otp.messaging.TopicNames;
import com.banking.otp.messaging.payload.IssueOtpPayload;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OtpCommandsConsumer {

    private static final Logger log = LoggerFactory.getLogger(OtpCommandsConsumer.class);

    private final EventParser parser;
    private final IssueOtpHandler handler;

    public OtpCommandsConsumer(EventParser parser, IssueOtpHandler handler) {
        this.parser = parser;
        this.handler = handler;
    }

    @KafkaListener(topics = TopicNames.OTP_COMMANDS)
    public void onMessage(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = parser.parse(record.value());

        if ("IssueOtp".equals(envelope.eventType())) {
            handler.handle(envelope, parser.payload(envelope, IssueOtpPayload.class));
        } else {
            // A newer producer may send commands we don't know yet. Ignore them, never fail.
            log.debug("Ignoring unknown command type {}", envelope.eventType());
        }
    }
}
package com.banking.otp.messaging;

public class EventPublishException extends RuntimeException {

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
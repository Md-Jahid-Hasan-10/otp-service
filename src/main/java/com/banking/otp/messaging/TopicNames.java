package com.banking.otp.messaging;

public final class TopicNames {

    private TopicNames() {}

    public static final String OTP_COMMANDS = "otp.commands";
    public static final String OTP_EVENTS = "otp.events";

    /** A message that failed all its retries is parked on "<topic>.DLT". */
    public static final String DLT_SUFFIX = ".DLT";
}
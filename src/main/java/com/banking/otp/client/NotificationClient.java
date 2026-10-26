package com.banking.otp.client;

public interface NotificationClient {

    void sendOtp(String email, String otp);
}
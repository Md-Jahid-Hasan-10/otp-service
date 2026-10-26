package com.banking.otp.service;

import com.banking.otp.dto.SendOtpRequest;
import com.banking.otp.dto.SendOtpResponse;
import com.banking.otp.dto.VerifyOtpRequest;
import com.banking.otp.dto.VerifyOtpResponse;

public interface OtpService {

    SendOtpResponse send(SendOtpRequest request);

    VerifyOtpResponse verify(VerifyOtpRequest request);
}
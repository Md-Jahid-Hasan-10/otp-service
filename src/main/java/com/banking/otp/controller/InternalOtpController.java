package com.banking.otp.controller;

import com.banking.otp.dto.SendOtpRequest;
import com.banking.otp.dto.SendOtpResponse;
import com.banking.otp.dto.VerifyOtpRequest;
import com.banking.otp.dto.VerifyOtpResponse;
import com.banking.otp.service.OtpService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/otp")
public class InternalOtpController {

    private final OtpService otpService;

    public InternalOtpController(OtpService otpService) {
        this.otpService = otpService;
    }

    @PostMapping("/send")
    public ResponseEntity<SendOtpResponse> send(@Valid @RequestBody SendOtpRequest request) {
        return ResponseEntity.ok(otpService.send(request));
    }

    @PostMapping("/verify")
    public ResponseEntity<VerifyOtpResponse> verify(@Valid @RequestBody VerifyOtpRequest request) {
        return ResponseEntity.ok(otpService.verify(request));
    }
}
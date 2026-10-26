package com.banking.otp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OtpServiceApplication {
	public static void main(String[] args) {
		SpringApplication.run(OtpServiceApplication.class, args);
	}
}
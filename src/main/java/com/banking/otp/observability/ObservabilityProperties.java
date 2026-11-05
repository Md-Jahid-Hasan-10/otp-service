package com.banking.otp.observability;   // change the package for each service

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.observability")
public record ObservabilityProperties(
        @DefaultValue("true") boolean logBodies,
        @DefaultValue("2048") int maxBodyLength
) {}
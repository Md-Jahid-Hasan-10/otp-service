package com.banking.otp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import io.micrometer.observation.ObservationRegistry;
@Configuration
public class RestClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder(ObservationRegistry observationRegistry) {
        // The registry makes every outgoing call a span and adds the trace headers.
        return RestClient.builder().observationRegistry(observationRegistry);
    }

    @Bean
    public RestClient notificationRestClient(@LoadBalanced RestClient.Builder builder,
                                             @Value("${app.notification.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);

        return builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
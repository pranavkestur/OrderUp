package com.orderup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kite")
public record KiteProperties(
        String apiKey,
        String apiSecret,
        String redirectUrl,
        String userId
) {}


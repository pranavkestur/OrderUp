package com.orderup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notify.telegram")
public record TelegramProperties(
        String botToken,
        String chatId
) {}


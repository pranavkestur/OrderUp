package com.orderup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kite")
public record KiteProperties(
        String apiKey,
        String apiSecret,
        String redirectUrl,
        String userId,
        /**
         * Filesystem path for the shared access-token JSON, used by
         * {@link com.orderup.auth.FileAccessTokenStore} so multiple OrderUp
         * apps on the same box (WACE scanner, Chartink webhook) can reuse a
         * single Kite login for the trading day. Blank/null disables the file
         * store (JPA-only mode, backward compatible).
         */
        String tokenStore
) {}


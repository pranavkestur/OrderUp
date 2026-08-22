package com.orderup.chartink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chartink-specific tunables. Bound from {@code chartink.*}.
 *
 * @param webhookSecret     Shared secret. Must match the {@code {secret}} path
 *                          variable on the {@code POST /chartink/webhook/{secret}}
 *                          endpoint. Blank → all requests rejected.
 * @param defaultQuantity   Order quantity per triggered symbol (default 1).
 */
@ConfigurationProperties(prefix = "chartink")
public record ChartinkProperties(
        String webhookSecret,
        Integer defaultQuantity
) {
    public int qtyOrOne() {
        return (defaultQuantity == null || defaultQuantity < 1) ? 1 : defaultQuantity;
    }
}


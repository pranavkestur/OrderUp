package com.orderup.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App-specific slice of {@code trading.*} configuration for the WACE scanner
 * process. Spring binds this against the same {@code trading} prefix as the
 * shared {@link com.orderup.config.TradingProperties} — both records receive
 * their respective known fields, unknown fields are silently ignored per record.
 *
 * <p>Fields kept here (not in common):
 * <ul>
 *   <li>{@link #scanCron} — cron for the legacy DAILY_MULTI + HOURLY_MULTI scan.</li>
 *   <li>{@link #waceScanCron} — cron for the WACE-only scan.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "trading")
public record TradingAppProperties(
        String scanCron,
        String waceScanCron
) {}


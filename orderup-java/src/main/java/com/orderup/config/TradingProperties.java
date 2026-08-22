package com.orderup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@ConfigurationProperties(prefix = "trading")
public record TradingProperties(
        String exchange,
        String product,
        String variety,
        String orderType,
        String scanCron,
        String waceScanCron,
        String schedulerZone,
        LocalTime marketOpen,
        LocalTime marketClose,
        List<String> watchlistFiles,
        List<String> extraSymbols,
        boolean allNseEq,
        double fallbackPricePctBand,
        List<LocalDate> holidays,
        boolean paperMode,
        Strategies strategies,
        MarketDataConfig marketData
) {
    public record Strategies(
            StrategyConfig hourly,
            StrategyConfig daily,
            WaceConfig waceHourly,
            WaceConfig waceDaily
    ) {}
}


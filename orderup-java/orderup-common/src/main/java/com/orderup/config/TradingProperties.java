package com.orderup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Common trading properties shared by all OrderUp apps (WACE scanner, Chartink
 * webhook receiver, future strategies). App-specific fields such as scan crons
 * live in each app's own {@code TradingAppProperties} record — Spring merges
 * bindings under the same {@code trading} prefix.
 */
@ConfigurationProperties(prefix = "trading")
public record TradingProperties(
        String exchange,
        String product,
        String variety,
        String orderType,
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
        MarketDataConfig marketData,
        RiskManagement riskManagement
) {
    /**
     * Bracket-order tunables. When {@code enabled} is true, every filled BUY
     * placed via {@link com.orderup.orders.OrderService#placeSignalOrderWithBracket}
     * is immediately followed by a Kite OCO (two-leg) GTT that carries both a
     * stop-loss (SL) and target (TGT) leg. When one leg triggers, Kite
     * automatically cancels the other.
     *
     * <p>Percentages are expressed as fractions ({@code 0.05 = 5%}) so config
     * reads naturally: {@code target-pct: 0.05  stop-loss-pct: 0.02}.
     */
    public record RiskManagement(
            boolean enabled,
            double targetPct,
            double stopLossPct
    ) {
        public double targetPctOrDefault()   { return targetPct   > 0 ? targetPct   : 0.05; }
        public double stopLossPctOrDefault() { return stopLossPct > 0 ? stopLossPct : 0.02; }
    }

    /**
     * Strategy parameter blocks. Kept in common (not the app module) because
     * {@link com.orderup.marketdata.CandleCacheWarmer} needs {@code waceDaily}
     * / {@code waceHourly} to know which (interval, historyDays) pairs to warm.
     * The Chartink app can leave {@code strategies} unset — the warmer bean is
     * disabled there via {@code trading.market-data.enabled=false}.
     */
    public record Strategies(
            StrategyConfig hourly,
            StrategyConfig daily,
            WaceConfig waceHourly,
            WaceConfig waceDaily
    ) {}
}


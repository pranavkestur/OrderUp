package com.orderup.config;

/**
 * Config knobs for one strategy instance (either 1-hour or 1-day timeframe).
 * Bound from `application.yml` under `trading.strategies.<name>`.
 */
public record StrategyConfig(
        String  name,           // dashboard label, e.g. HOURLY_MULTI
        boolean enabled,
        int     quantity,       // order size per signal
        String  interval,       // Kite interval — "60minute" or "day"
        int     historyDays,    // calendar-days of history to request from Kite
        boolean crossover,      // true = require crossover on this bar, false = state check

        int    rsiPeriod,       double rsiBuyLevel,   double rsiSellLevel,
        int    cciPeriod,       double cciBuyLevel,   double cciSellLevel,
        int    wrPeriod,        double wrBuyLevel,    double wrSellLevel,
        int    macdFast,        int    macdSlow,      int    macdSignal,
        int    adxPeriod,       double adxMin
) {}


package com.orderup.config;

/**
 * Config knobs for a WACE strategy instance (either 1-hour or 1-day timeframe).
 * Bound from {@code application.yml} under {@code trading.strategies.wace-hourly}
 * and {@code trading.strategies.wace-daily}.
 *
 * <p>WACE is a BUY-only qualification strategy. Rules (all AND-ed, evaluated on
 * the just-formed bar {@code N}):
 * <ol>
 *   <li>{@code CCI(cciPeriod)[N] > cciBuyLevel}</li>
 *   <li>{@code WilliamsR(wrPeriod)[N] > wrBuyLevel}</li>
 *   <li>{@code ADX(adxPeriod)[N] > adxMin}</li>
 *   <li>{@code +DI(adxPeriod)[N] > -DI(adxPeriod)[N]}</li>
 *   <li>{@code Close[N] > High[N - prevHighLookback]}</li>
 *   <li>{@code Volume[N] > SMA(Volume, volumeSmaPeriod)[N]}</li>
 *   <li>{@code EMA(Close, emaFast)} crossed above {@code EMA(Close, emaMid)} on bar N</li>
 *   <li>{@code EMA(Close, emaMid)[N] > EMA(Close, emaSlow)[N]}</li>
 * </ol>
 */
public record WaceConfig(
        String  name,               // dashboard label, e.g. WACE_DAILY / WACE_HOURLY
        boolean enabled,
        int     quantity,           // order size per signal
        String  interval,           // Kite interval — "60minute" or "day"
        int     historyDays,        // calendar-days of history to request from Kite

        int    cciPeriod,           double cciBuyLevel,
        int    wrPeriod,            double wrBuyLevel,
        int    adxPeriod,           double adxMin,

        int    emaFast,             // e.g. 10
        int    emaMid,              // e.g. 20 (fast crosses above this)
        int    emaSlow,             // e.g. 50 (mid must be above this)

        int    volumeSmaPeriod,     // e.g. 20
        int    prevHighLookback     // e.g. 1 ("1 day ago High")
) {}


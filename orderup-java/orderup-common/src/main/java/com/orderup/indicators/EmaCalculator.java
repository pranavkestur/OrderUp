package com.orderup.indicators;

import com.orderup.marketdata.Candle;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin façade over {@link MacdCalculator#ema(List, int)} plus a simple-moving-average
 * helper for volume (or any numeric series). Kept separate so callers don't have to
 * depend on MACD internals just to get an EMA/SMA series.
 */
public final class EmaCalculator {

    private EmaCalculator() {}

    /** EMA of closing prices from a candle series, SMA-initialised. */
    public static List<Double> emaOfClose(List<Candle> candles, int period) {
        List<Double> closes = new ArrayList<>(candles.size());
        for (Candle c : candles) closes.add(c.close());
        return MacdCalculator.ema(closes, period);
    }

    /** EMA of an arbitrary numeric series (delegates to MacdCalculator). */
    public static List<Double> ema(List<Double> series, int period) {
        return MacdCalculator.ema(series, period);
    }

    /**
     * Simple moving average of candle volume over {@code period} bars.
     * Leading entries where a full window is unavailable are {@link Double#NaN}.
     */
    public static List<Double> smaVolume(List<Candle> candles, int period) {
        int n = candles.size();
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(Double.NaN);
        if (period <= 0 || n < period) return out;

        double sum = 0;
        for (int i = 0; i < period; i++) sum += candles.get(i).volume();
        out.set(period - 1, sum / period);
        for (int i = period; i < n; i++) {
            sum += candles.get(i).volume() - candles.get(i - period).volume();
            out.set(i, sum / period);
        }
        return out;
    }
}


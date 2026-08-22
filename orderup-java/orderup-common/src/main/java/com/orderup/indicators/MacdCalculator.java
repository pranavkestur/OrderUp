package com.orderup.indicators;

import com.orderup.marketdata.Candle;

import java.util.ArrayList;
import java.util.List;

/**
 * MACD:
 *   fastEma  = EMA(close, fast)     — default 12
 *   slowEma  = EMA(close, slow)     — default 26
 *   macd     = fastEma - slowEma
 *   signal   = EMA(macd, signal)    — default 9
 *   hist     = macd - signal
 *
 * EMAs are SMA-initialised for numerical stability over long series.
 */
public final class MacdCalculator {

    private MacdCalculator() {}

    public record MacdResult(List<Double> macd, List<Double> signal, List<Double> histogram) {}

    public static MacdResult calculate(List<Candle> candles, int fast, int slow, int signalPeriod) {
        int n = candles.size();
        List<Double> closes = new ArrayList<>(n);
        for (Candle c : candles) closes.add(c.close());

        List<Double> emaFast = ema(closes, fast);
        List<Double> emaSlow = ema(closes, slow);

        List<Double> macd = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double f = emaFast.get(i), s = emaSlow.get(i);
            macd.add(Double.isNaN(f) || Double.isNaN(s) ? Double.NaN : f - s);
        }

        List<Double> signal = ema(macd, signalPeriod);

        List<Double> hist = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double m = macd.get(i), s = signal.get(i);
            hist.add(Double.isNaN(m) || Double.isNaN(s) ? Double.NaN : m - s);
        }
        return new MacdResult(macd, signal, hist);
    }

    /** SMA-initialised EMA over an input series (may contain leading NaNs). */
    public static List<Double> ema(List<Double> series, int period) {
        int n = series.size();
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(Double.NaN);
        if (n < period) return out;

        double sum = 0;
        int count = 0;
        int i = 0;
        for (; i < n && count < period; i++) {
            double v = series.get(i);
            if (Double.isNaN(v)) continue;
            sum += v;
            count++;
        }
        if (count < period) return out;
        double ema = sum / period;
        out.set(i - 1, ema);
        double alpha = 2.0 / (period + 1);
        for (; i < n; i++) {
            double v = series.get(i);
            if (Double.isNaN(v)) { out.set(i, ema); continue; }
            ema = alpha * v + (1 - alpha) * ema;
            out.set(i, ema);
        }
        return out;
    }
}


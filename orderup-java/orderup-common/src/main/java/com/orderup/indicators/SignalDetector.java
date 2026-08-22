package com.orderup.indicators;

import java.util.List;

/**
 * Detects crossover signals matching the Python POC rules exactly.
 *
 * CCI:
 *   BUY  : prev >= -100 && curr <  -100
 *   SELL : prev <=  100 && curr >   100
 *
 * Williams %R (kept as in POC per user's instruction):
 *   BUY  : prev >= -80 && curr <  -80
 *   SELL : prev >= -20 && curr <  -20
 */
public final class SignalDetector {

    public enum Signal { BUY, SELL, NONE }

    private SignalDetector() {}

    public static Signal cci(List<Double> cci) {
        if (cci == null || cci.size() < 2) return Signal.NONE;
        Double curr = last(cci);
        Double prev = secondLast(cci);
        if (curr == null || prev == null) return Signal.NONE;
        if (prev <= 100 && curr > 100) return Signal.SELL;
        if (prev >= -100 && curr < -100) return Signal.BUY;
        return Signal.NONE;
    }

    public static Signal williamsR(List<Double> wr) {
        if (wr == null || wr.size() < 2) return Signal.NONE;
        Double curr = last(wr);
        Double prev = secondLast(wr);
        if (curr == null || prev == null) return Signal.NONE;
        if (prev >= -20 && curr < -20) return Signal.SELL;
        if (prev >= -80 && curr < -80) return Signal.BUY;
        return Signal.NONE;
    }

    private static Double last(List<Double> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Double v = list.get(i);
            if (v != null && !Double.isNaN(v)) return v;
        }
        return null;
    }

    private static Double secondLast(List<Double> list) {
        boolean seen = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            Double v = list.get(i);
            if (v == null || Double.isNaN(v)) continue;
            if (!seen) { seen = true; continue; }
            return v;
        }
        return null;
    }
}


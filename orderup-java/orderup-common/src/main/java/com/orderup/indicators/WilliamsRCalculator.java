package com.orderup.indicators;

import com.orderup.marketdata.Candle;

import java.util.ArrayList;
import java.util.List;

/**
 * Williams %R.
 *   %R = ((HighestHigh - Close) / (HighestHigh - LowestLow)) * -100
 * Values in [-100, 0].
 */
public final class WilliamsRCalculator {

    private WilliamsRCalculator() {}

    public static List<Double> calculate(List<Candle> candles, int period) {
        int n = candles.size();
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (i < period - 1) { out.add(Double.NaN); continue; }

            double hh = Double.NEGATIVE_INFINITY;
            double ll = Double.POSITIVE_INFINITY;
            for (int j = i - period + 1; j <= i; j++) {
                hh = Math.max(hh, candles.get(j).high());
                ll = Math.min(ll, candles.get(j).low());
            }
            double range = hh - ll;
            if (range == 0.0) { out.add(Double.NaN); continue; }
            out.add(((hh - candles.get(i).close()) / range) * -100.0);
        }
        return out;
    }
}


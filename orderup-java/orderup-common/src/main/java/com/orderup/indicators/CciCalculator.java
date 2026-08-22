package com.orderup.indicators;

import com.orderup.marketdata.Candle;

import java.util.ArrayList;
import java.util.List;

/**
 * Commodity Channel Index.
 *   TP = (H + L + C) / 3
 *   CCI = (TP - SMA(TP, n)) / (0.015 * MeanDeviation(TP, n))
 */
public final class CciCalculator {

    private CciCalculator() {}

    public static List<Double> calculate(List<Candle> candles, int period) {
        int n = candles.size();
        List<Double> tp = new ArrayList<>(n);
        for (Candle c : candles) {
            tp.add((c.high() + c.low() + c.close()) / 3.0);
        }

        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (i < period - 1) { out.add(Double.NaN); continue; }

            double sum = 0.0;
            for (int j = i - period + 1; j <= i; j++) sum += tp.get(j);
            double sma = sum / period;

            double md = 0.0;
            for (int j = i - period + 1; j <= i; j++) md += Math.abs(tp.get(j) - sma);
            md /= period;

            if (md == 0.0) { out.add(Double.NaN); continue; }
            out.add((tp.get(i) - sma) / (0.015 * md));
        }
        return out;
    }
}


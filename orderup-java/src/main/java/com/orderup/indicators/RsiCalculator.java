package com.orderup.indicators;

import com.orderup.marketdata.Candle;

import java.util.ArrayList;
import java.util.List;

/**
 * Wilder's Relative Strength Index.
 *   RSI = 100 - (100 / (1 + avgGain/avgLoss))
 * where avgGain / avgLoss are Wilder-smoothed averages of positive / negative
 * close-to-close moves over `period` bars.
 */
public final class RsiCalculator {

    private RsiCalculator() {}

    public static List<Double> calculate(List<Candle> candles, int period) {
        int n = candles.size();
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(Double.NaN);
        if (n <= period) return out;

        double sumGain = 0, sumLoss = 0;
        for (int i = 1; i <= period; i++) {
            double change = candles.get(i).close() - candles.get(i - 1).close();
            if (change >= 0) sumGain += change; else sumLoss -= change;
        }
        double avgGain = sumGain / period;
        double avgLoss = sumLoss / period;
        out.set(period, rsi(avgGain, avgLoss));

        for (int i = period + 1; i < n; i++) {
            double change = candles.get(i).close() - candles.get(i - 1).close();
            double gain = change > 0 ? change : 0;
            double loss = change < 0 ? -change : 0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
            out.set(i, rsi(avgGain, avgLoss));
        }
        return out;
    }

    private static double rsi(double avgGain, double avgLoss) {
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - 100.0 / (1.0 + rs);
    }
}


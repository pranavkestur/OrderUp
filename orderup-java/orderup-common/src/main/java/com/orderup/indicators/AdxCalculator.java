package com.orderup.indicators;

import com.orderup.marketdata.Candle;

import java.util.ArrayList;
import java.util.List;

/**
 * Average Directional Index (Wilder). Returns the three parallel series:
 *   +DI, -DI, ADX
 * ADX becomes valid at index (2*period - 1) — needs at least 2*period+1 bars.
 */
public final class AdxCalculator {

    private AdxCalculator() {}

    public record AdxResult(List<Double> adx, List<Double> plusDi, List<Double> minusDi) {}

    public static AdxResult calculate(List<Candle> candles, int period) {
        int n = candles.size();
        List<Double> adx     = new ArrayList<>(n);
        List<Double> plusDi  = new ArrayList<>(n);
        List<Double> minusDi = new ArrayList<>(n);
        for (int i = 0; i < n; i++) { adx.add(Double.NaN); plusDi.add(Double.NaN); minusDi.add(Double.NaN); }
        if (n < 2 * period + 1) return new AdxResult(adx, plusDi, minusDi);

        double[] tr = new double[n];
        double[] pDm = new double[n];
        double[] mDm = new double[n];

        for (int i = 1; i < n; i++) {
            Candle c = candles.get(i);
            Candle p = candles.get(i - 1);
            tr[i] = Math.max(c.high() - c.low(),
                     Math.max(Math.abs(c.high() - p.close()), Math.abs(c.low() - p.close())));
            double up   = c.high() - p.high();
            double down = p.low()  - c.low();
            pDm[i] = (up   > down && up   > 0) ? up   : 0;
            mDm[i] = (down > up   && down > 0) ? down : 0;
        }

        // Initial Wilder sums over bars 1..period
        double sTr = 0, sPDm = 0, sMDm = 0;
        for (int i = 1; i <= period; i++) { sTr += tr[i]; sPDm += pDm[i]; sMDm += mDm[i]; }

        double[] dx = new double[n];
        double pDi = sTr == 0 ? 0 : 100.0 * sPDm / sTr;
        double mDi = sTr == 0 ? 0 : 100.0 * sMDm / sTr;
        plusDi.set(period, pDi);
        minusDi.set(period, mDi);
        dx[period] = pDi + mDi == 0 ? 0 : 100.0 * Math.abs(pDi - mDi) / (pDi + mDi);

        for (int i = period + 1; i < n; i++) {
            sTr  = sTr  - sTr  / period + tr[i];
            sPDm = sPDm - sPDm / period + pDm[i];
            sMDm = sMDm - sMDm / period + mDm[i];
            pDi = sTr == 0 ? 0 : 100.0 * sPDm / sTr;
            mDi = sTr == 0 ? 0 : 100.0 * sMDm / sTr;
            plusDi.set(i, pDi);
            minusDi.set(i, mDi);
            dx[i] = pDi + mDi == 0 ? 0 : 100.0 * Math.abs(pDi - mDi) / (pDi + mDi);
        }

        // Wilder-smoothed ADX starting at index 2*period - 1
        double adxVal = 0;
        for (int i = period; i < 2 * period; i++) adxVal += dx[i];
        adxVal /= period;
        adx.set(2 * period - 1, adxVal);
        for (int i = 2 * period; i < n; i++) {
            adxVal = (adxVal * (period - 1) + dx[i]) / period;
            adx.set(i, adxVal);
        }
        return new AdxResult(adx, plusDi, minusDi);
    }
}


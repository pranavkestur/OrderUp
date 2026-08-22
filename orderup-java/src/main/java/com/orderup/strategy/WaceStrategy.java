package com.orderup.strategy;

import com.orderup.config.WaceConfig;
import com.orderup.indicators.AdxCalculator;
import com.orderup.indicators.CciCalculator;
import com.orderup.indicators.EmaCalculator;
import com.orderup.indicators.WilliamsRCalculator;
import com.orderup.marketdata.Candle;
import com.orderup.strategy.MultiIndicatorStrategy.Result;
import com.orderup.strategy.MultiIndicatorStrategy.Signal;

import java.util.List;

/**
 * WACE — a BUY-only qualification strategy combining trend, momentum, breakout
 * and volume filters with a fast/mid EMA crossover trigger.
 *
 * <p>Reuses {@link MultiIndicatorStrategy.Result} / {@link MultiIndicatorStrategy.Signal}
 * so downstream watchlist / order / notify code paths remain unchanged.
 *
 * <p>All conditions evaluated on the just-formed bar {@code N}:
 * <ol>
 *   <li>{@code CCI(cciPeriod)[N] > cciBuyLevel}</li>
 *   <li>{@code WilliamsR(wrPeriod)[N] > wrBuyLevel}</li>
 *   <li>{@code ADX(adxPeriod)[N] > adxMin}</li>
 *   <li>{@code +DI(adxPeriod)[N] > -DI(adxPeriod)[N]}</li>
 *   <li>{@code Close[N] > High[N - prevHighLookback]}</li>
 *   <li>{@code Volume[N] > SMA(Volume, volumeSmaPeriod)[N]}</li>
 *   <li>{@code EMA(Close, emaFast)} strictly crossed above {@code EMA(Close, emaMid)}
 *       on bar N — i.e. {@code fast[N-1] <= mid[N-1]} AND {@code fast[N] > mid[N]}</li>
 *   <li>{@code EMA(Close, emaMid)[N] > EMA(Close, emaSlow)[N]}</li>
 * </ol>
 */
public final class WaceStrategy {

    private WaceStrategy() {}

    public static Result evaluate(List<Candle> candles, WaceConfig cfg) {
        int n = candles.size();
        if (n < 2 || cfg.prevHighLookback() < 1 || n <= cfg.prevHighLookback()) {
            return MultiIndicatorStrategy.NONE;
        }

        // --- Indicators ------------------------------------------------------
        List<Double> cci = CciCalculator.calculate(candles, cfg.cciPeriod());
        List<Double> wr  = WilliamsRCalculator.calculate(candles, cfg.wrPeriod());
        AdxCalculator.AdxResult adx = AdxCalculator.calculate(candles, cfg.adxPeriod());
        List<Double> emaFast = EmaCalculator.emaOfClose(candles, cfg.emaFast());
        List<Double> emaMid  = EmaCalculator.emaOfClose(candles, cfg.emaMid());
        List<Double> emaSlow = EmaCalculator.emaOfClose(candles, cfg.emaSlow());
        List<Double> volSma  = EmaCalculator.smaVolume(candles, cfg.volumeSmaPeriod());

        int i = n - 1;
        int p = i - 1;

        double cciCurr    = cci.get(i);
        double wrCurr     = wr.get(i);
        double adxCurr    = adx.adx().get(i);
        double plusDiCurr = adx.plusDi().get(i);
        double minusDiCurr= adx.minusDi().get(i);
        double emaFastCurr= emaFast.get(i);
        double emaFastPrev= emaFast.get(p);
        double emaMidCurr = emaMid.get(i);
        double emaMidPrev = emaMid.get(p);
        double emaSlowCurr= emaSlow.get(i);
        double volSmaCurr = volSma.get(i);

        if (anyNaN(cciCurr, wrCurr, adxCurr, plusDiCurr, minusDiCurr,
                   emaFastCurr, emaFastPrev, emaMidCurr, emaMidPrev, emaSlowCurr, volSmaCurr)) {
            return MultiIndicatorStrategy.NONE;
        }

        Candle curr = candles.get(i);
        Candle ref  = candles.get(i - cfg.prevHighLookback());
        double closeCurr = curr.close();
        double prevHigh  = ref.high();
        long   volCurr   = curr.volume();

        // --- Rules -----------------------------------------------------------
        boolean cciOk        = cciCurr > cfg.cciBuyLevel();
        boolean wrOk         = wrCurr  > cfg.wrBuyLevel();
        boolean adxOk        = adxCurr > cfg.adxMin();
        boolean diOk         = plusDiCurr > minusDiCurr;
        boolean breakoutOk   = closeCurr > prevHigh;
        boolean volumeOk     = volCurr > volSmaCurr;
        boolean crossOk      = (emaFastPrev <= emaMidPrev) && (emaFastCurr > emaMidCurr);
        boolean stackOk      = emaMidCurr > emaSlowCurr;

        if (cciOk && wrOk && adxOk && diOk && breakoutOk && volumeOk && crossOk && stackOk) {
            String reason = String.format(
                    "CCI(%d) %.1f>%.0f | W%%R(%d) %.1f>%.0f | ADX(%d) %.1f>%.0f | +DI %.1f>-DI %.1f | " +
                    "Close %.2f>PrevHigh %.2f | Vol %d>SMA%d %.0f | EMA%d %.2f x EMA%d %.2f | EMA%d>EMA%d %.2f>%.2f",
                    cfg.cciPeriod(), cciCurr, cfg.cciBuyLevel(),
                    cfg.wrPeriod(),  wrCurr,  cfg.wrBuyLevel(),
                    cfg.adxPeriod(), adxCurr, cfg.adxMin(),
                    plusDiCurr, minusDiCurr,
                    closeCurr, prevHigh,
                    volCurr, cfg.volumeSmaPeriod(), volSmaCurr,
                    cfg.emaFast(), emaFastCurr, cfg.emaMid(), emaMidCurr,
                    cfg.emaMid(), cfg.emaSlow(), emaMidCurr, emaSlowCurr);
            return new Result(Signal.BUY, reason);
        }
        return MultiIndicatorStrategy.NONE;
    }

    private static boolean anyNaN(double... vals) {
        for (double v : vals) if (Double.isNaN(v)) return true;
        return false;
    }
}


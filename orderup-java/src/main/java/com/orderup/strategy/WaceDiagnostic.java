package com.orderup.strategy;

import com.orderup.config.WaceConfig;
import com.orderup.indicators.AdxCalculator;
import com.orderup.indicators.CciCalculator;
import com.orderup.indicators.EmaCalculator;
import com.orderup.indicators.WilliamsRCalculator;
import com.orderup.marketdata.Candle;

import java.util.List;

/**
 * Debug/diagnostic evaluator for {@link WaceStrategy}. Instead of returning a
 * single {@code BUY/NONE} verdict, it returns a per-condition pass/fail array
 * plus the raw values that fed each check. Used by
 * {@code /diag/wace/daily-scan} to build funnel stats without touching the
 * live strategy code.
 */
public final class WaceDiagnostic {

    private WaceDiagnostic() {}

    public record ConditionResult(
            String  name,
            boolean pass,
            double  lhs,
            double  rhs
    ) {}

    public record Report(
            boolean qualified,
            int     passedCount,
            List<ConditionResult> conditions
    ) {}

    public static Report evaluate(List<Candle> candles, WaceConfig cfg) {
        int n = candles.size();
        if (n < 2 || cfg.prevHighLookback() < 1 || n <= cfg.prevHighLookback()) {
            return new Report(false, 0, List.of());
        }

        List<Double> cci = CciCalculator.calculate(candles, cfg.cciPeriod());
        List<Double> wr  = WilliamsRCalculator.calculate(candles, cfg.wrPeriod());
        AdxCalculator.AdxResult adx = AdxCalculator.calculate(candles, cfg.adxPeriod());
        List<Double> emaFast = EmaCalculator.emaOfClose(candles, cfg.emaFast());
        List<Double> emaMid  = EmaCalculator.emaOfClose(candles, cfg.emaMid());
        List<Double> emaSlow = EmaCalculator.emaOfClose(candles, cfg.emaSlow());
        List<Double> volSma  = EmaCalculator.smaVolume(candles, cfg.volumeSmaPeriod());

        int i = n - 1;
        int p = i - 1;

        double cciCurr     = cci.get(i);
        double wrCurr      = wr.get(i);
        double adxCurr     = adx.adx().get(i);
        double plusDiCurr  = adx.plusDi().get(i);
        double minusDiCurr = adx.minusDi().get(i);
        double emaFastCurr = emaFast.get(i);
        double emaFastPrev = emaFast.get(p);
        double emaMidCurr  = emaMid.get(i);
        double emaMidPrev  = emaMid.get(p);
        double emaSlowCurr = emaSlow.get(i);
        double volSmaCurr  = volSma.get(i);

        if (anyNaN(cciCurr, wrCurr, adxCurr, plusDiCurr, minusDiCurr,
                   emaFastCurr, emaFastPrev, emaMidCurr, emaMidPrev, emaSlowCurr, volSmaCurr)) {
            return new Report(false, 0, List.of());
        }

        Candle curr = candles.get(i);
        Candle ref  = candles.get(i - cfg.prevHighLookback());
        double closeCurr = curr.close();
        double prevHigh  = ref.high();
        double volCurr   = curr.volume();

        // Diagnostic: EMA "cross" is expressed as two sub-checks so we can see which
        // half fails (usually "fast already above mid, not a fresh cross").
        boolean crossPart1 = emaFastPrev <= emaMidPrev;      // was below/equal
        boolean crossPart2 = emaFastCurr >  emaMidCurr;      // now strictly above
        boolean crossOk    = crossPart1 && crossPart2;

        List<ConditionResult> cs = List.of(
            new ConditionResult("CCI>buyLevel",     cciCurr > cfg.cciBuyLevel(), cciCurr, cfg.cciBuyLevel()),
            new ConditionResult("WR>buyLevel",      wrCurr  > cfg.wrBuyLevel(),  wrCurr,  cfg.wrBuyLevel()),
            new ConditionResult("ADX>min",          adxCurr > cfg.adxMin(),      adxCurr, cfg.adxMin()),
            new ConditionResult("+DI>-DI",          plusDiCurr > minusDiCurr,    plusDiCurr, minusDiCurr),
            new ConditionResult("Close>PrevHigh",   closeCurr > prevHigh,        closeCurr, prevHigh),
            new ConditionResult("Vol>SMA(Vol)",     volCurr > volSmaCurr,        volCurr, volSmaCurr),
            new ConditionResult("EMAfastPrev<=EMAmidPrev", crossPart1,           emaFastPrev, emaMidPrev),
            new ConditionResult("EMAfastCurr>EMAmidCurr",  crossPart2,           emaFastCurr, emaMidCurr),
            new ConditionResult("EMAmid>EMAslow",   emaMidCurr > emaSlowCurr,    emaMidCurr, emaSlowCurr)
        );

        int passed = 0;
        for (ConditionResult c : cs) if (c.pass()) passed++;
        // Full qualification = crossOk (both parts) plus all other 7 = 9 sub-checks pass.
        boolean qualified = passed == cs.size();
        return new Report(qualified, passed, cs);
    }

    private static boolean anyNaN(double... vals) {
        for (double v : vals) if (Double.isNaN(v)) return true;
        return false;
    }
}


package com.orderup.strategy;

import com.orderup.config.StrategyConfig;
import com.orderup.indicators.AdxCalculator;
import com.orderup.indicators.CciCalculator;
import com.orderup.indicators.MacdCalculator;
import com.orderup.indicators.RsiCalculator;
import com.orderup.indicators.WilliamsRCalculator;
import com.orderup.marketdata.Candle;

import java.util.List;

/**
 * Multi-indicator, multi-timeframe strategy. Fires BUY when RSI/CCI/W%R/MACD/ADX
 * all agree bullishly; SELL when all agree bearishly.
 *
 * Two evaluation modes:
 *   CROSSOVER — indicators must CROSS the threshold on the just-formed bar
 *               (previous bar was on the other side). Used for 1-hour.
 *   STATE     — indicators must currently BE on the required side of the threshold.
 *               Used for 1-day.
 *
 * MACD and ADX use STATE checks in both modes.
 */
public final class MultiIndicatorStrategy {

    private MultiIndicatorStrategy() {}

    public enum Signal { BUY, SELL, NONE }
    public record Result(Signal signal, String reason) {}
    public static final Result NONE = new Result(Signal.NONE, "");

    public static Result evaluate(List<Candle> candles, StrategyConfig cfg) {
        int n = candles.size();
        if (n < 2) return NONE;

        List<Double> rsi = RsiCalculator.calculate(candles, cfg.rsiPeriod());
        List<Double> cci = CciCalculator.calculate(candles, cfg.cciPeriod());
        List<Double> wr  = WilliamsRCalculator.calculate(candles, cfg.wrPeriod());
        MacdCalculator.MacdResult macd = MacdCalculator.calculate(
                candles, cfg.macdFast(), cfg.macdSlow(), cfg.macdSignal());
        AdxCalculator.AdxResult adx = AdxCalculator.calculate(candles, cfg.adxPeriod());

        double rsiPrev = tail2(rsi), rsiCurr = tail1(rsi);
        double cciPrev = tail2(cci), cciCurr = tail1(cci);
        double wrPrev  = tail2(wr),  wrCurr  = tail1(wr);
        double macdCurr = tail1(macd.macd());
        double sigCurr  = tail1(macd.signal());
        double adxCurr  = tail1(adx.adx());

        if (anyNaN(rsiCurr, cciCurr, wrCurr, macdCurr, sigCurr, adxCurr)) return NONE;
        if (cfg.crossover() && anyNaN(rsiPrev, cciPrev, wrPrev)) return NONE;

        // -------- BUY --------
        boolean rsiBuy = cfg.crossover()
                ? (rsiPrev < cfg.rsiBuyLevel() && rsiCurr >= cfg.rsiBuyLevel())
                : (rsiCurr > cfg.rsiBuyLevel());
        boolean cciBuy = cfg.crossover()
                ? (cciPrev < cfg.cciBuyLevel() && cciCurr >= cfg.cciBuyLevel())
                : (cciCurr > cfg.cciBuyLevel());
        boolean wrBuy  = cfg.crossover()
                ? (wrPrev  < cfg.wrBuyLevel() && wrCurr  >= cfg.wrBuyLevel())
                : (wrCurr  > cfg.wrBuyLevel());
        boolean macdBuy = macdCurr > 0 && sigCurr < macdCurr;
        boolean adxOk   = adxCurr > cfg.adxMin();

        if (rsiBuy && cciBuy && wrBuy && macdBuy && adxOk) {
            return new Result(Signal.BUY, String.format(
                    "RSI %.1f CCI %.1f W%%R %.1f MACD %.3f sig %.3f ADX %.1f",
                    rsiCurr, cciCurr, wrCurr, macdCurr, sigCurr, adxCurr));
        }

        // -------- SELL --------
        boolean rsiSell = cfg.crossover()
                ? (rsiPrev > cfg.rsiSellLevel() && rsiCurr <= cfg.rsiSellLevel())
                : (rsiCurr < cfg.rsiSellLevel());
        boolean cciSell = cfg.crossover()
                ? (cciPrev > cfg.cciSellLevel() && cciCurr <= cfg.cciSellLevel())
                : (cciCurr < cfg.cciSellLevel());
        boolean wrSell  = cfg.crossover()
                ? (wrPrev  > cfg.wrSellLevel() && wrCurr  <= cfg.wrSellLevel())
                : (wrCurr  < cfg.wrSellLevel());
        boolean macdSell = macdCurr < 0 && sigCurr > macdCurr;

        if (rsiSell && cciSell && wrSell && macdSell && adxOk) {
            return new Result(Signal.SELL, String.format(
                    "RSI %.1f CCI %.1f W%%R %.1f MACD %.3f sig %.3f ADX %.1f",
                    rsiCurr, cciCurr, wrCurr, macdCurr, sigCurr, adxCurr));
        }

        return NONE;
    }

    private static double tail1(List<Double> list) {
        return list == null || list.isEmpty() ? Double.NaN : list.get(list.size() - 1);
    }
    private static double tail2(List<Double> list) {
        return list == null || list.size() < 2 ? Double.NaN : list.get(list.size() - 2);
    }
    private static boolean anyNaN(double... vals) {
        for (double v : vals) if (Double.isNaN(v)) return true;
        return false;
    }
}


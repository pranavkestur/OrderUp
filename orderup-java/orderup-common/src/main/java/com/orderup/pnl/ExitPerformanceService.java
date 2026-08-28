package com.orderup.pnl;

import com.orderup.marketdata.InstrumentService;
import com.orderup.orders.OrderRecord;
import com.orderup.orders.OrderRecordRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.HistoricalData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/**
 * Post-exit tracking: for every closed SELL, records the underlying's price
 * 1 / 7 / 30 trading days after the exit and derives ROI %. Feeds the
 * "SL discipline / TGT discipline / best exit strategy" dashboard cards.
 *
 * <p>Runs nightly after market close. Idempotent — re-runs only fill in
 * missing price_Nd columns and skip rows already fully populated.
 *
 * <p>Kite's historical-data API is called directly (bypassing
 * {@code HistoricalDataService}) so this works in the Chartink app even
 * though {@code trading.market-data.enabled=false} there. Volume is small
 * (≤ number of SELLs in the past 30 trading days per run) so the
 * rate-limiter is unnecessary.
 */
@Service
public class ExitPerformanceService {

    private static final Logger log = LoggerFactory.getLogger(ExitPerformanceService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Anything older than this — measured from the SELL's placedAt — is
     * considered "final" (30d ROI already captured) and skipped. Keeping
     * this a touch above 30 trading days (~45 calendar days) covers long
     * weekends / holiday clusters.
     */
    private static final Duration MAX_LOOKBACK = Duration.ofDays(45);

    private final OrderRecordRepository orders;
    private final ExitPerformanceRepository exitRepo;
    private final KiteConnect kite;
    private final InstrumentService instruments;

    public ExitPerformanceService(OrderRecordRepository orders,
                                  ExitPerformanceRepository exitRepo,
                                  KiteConnect kite,
                                  InstrumentService instruments) {
        this.orders = orders;
        this.exitRepo = exitRepo;
        this.kite = kite;
        this.instruments = instruments;
    }

    /**
     * Nightly cron: refresh post-exit tracking for every SELL in the last
     * {@link #MAX_LOOKBACK} whose 30d slot isn't filled yet. 17:00 IST is
     * comfortably after market close (15:30) and after any post-market
     * settlement reporting — Kite's day-candle for {@code today} is stable
     * by then.
     */
    @Scheduled(cron = "${trading.exit-performance-cron:0 0 17 * * MON-FRI}",
               zone = "${trading.scheduler-zone:Asia/Kolkata}")
    public void nightlyRefresh() {
        try {
            int touched = refreshPending();
            if (touched > 0) log.info("ExitPerformance nightly refresh touched {} rows.", touched);
        } catch (Throwable t) {
            log.warn("ExitPerformance nightly refresh failed: {}", t.getMessage(), t);
        }
    }

    /**
     * Public entry point — also invoked from the admin controller for
     * on-demand refresh. Returns the number of ExitPerformance rows
     * created-or-updated.
     */
    public int refreshPending() {
        Instant cutoff = Instant.now().minus(MAX_LOOKBACK);
        List<OrderRecord> sells = new ArrayList<>();
        for (OrderRecord o : orders.findAll()) {
            if (!"SELL".equalsIgnoreCase(o.getSide())) continue;
            if (o.getPlacedAt() == null || o.getPlacedAt().isBefore(cutoff)) continue;
            if (o.getAvgFillPrice() == null || o.getAvgFillPrice() <= 0) continue;
            sells.add(o);
        }
        if (sells.isEmpty()) return 0;

        Map<Long, ExitPerformance> existing = new HashMap<>();
        for (ExitPerformance ep : exitRepo.findAll()) existing.put(ep.getId(), ep);

        Instant now = Instant.now();
        int changed = 0;
        for (OrderRecord sell : sells) {
            ExitPerformance ep = existing.get(sell.getId());
            if (ep == null) {
                ep = new ExitPerformance(sell.getId(), sell.getSymbol(),
                        sell.getPlacedAt(), sell.getAvgFillPrice(), sell.getExitType());
            } else {
                // Refresh cheap copy-through columns (exitType may have been
                // backfilled after the row was first created).
                if (ep.getExitType() == null && sell.getExitType() != null) {
                    ep.setExitType(sell.getExitType());
                }
            }

            boolean need1  = ep.getPrice1d()  == null && daysSince(sell.getPlacedAt())  >= 1;
            boolean need7  = ep.getPrice7d()  == null && daysSince(sell.getPlacedAt())  >= 7;
            boolean need30 = ep.getPrice30d() == null && daysSince(sell.getPlacedAt())  >= 30;
            if (!need1 && !need7 && !need30) continue;

            Long token = instruments.tokenFor(sell.getSymbol());
            if (token == null) {
                log.debug("ExitPerformance: no instrument token for {}, skipping", sell.getSymbol());
                continue;
            }
            try {
                Map<Integer, Double> closes = fetchTradingDayCloses(token, sell.getPlacedAt(), 35);
                if (need1)  applyClose(ep, 1,  closes, ep.getExitPrice());
                if (need7)  applyClose(ep, 7,  closes, ep.getExitPrice());
                if (need30) applyClose(ep, 30, closes, ep.getExitPrice());
                ep.setUpdatedAt(now);
                exitRepo.save(ep);
                changed++;
            } catch (Throwable t) {
                log.warn("ExitPerformance: fetch failed for {} (token={}): {}",
                        sell.getSymbol(), token, t.getMessage());
            }
        }
        return changed;
    }

    private static void applyClose(ExitPerformance ep, int n,
                                   Map<Integer, Double> closes, double exitPx) {
        Double px = closes.get(n);
        if (px == null || px <= 0 || exitPx <= 0) return;
        double roi = ((px - exitPx) / exitPx) * 100.0;
        switch (n) {
            case 1  -> { ep.setPrice1d(px);  ep.setRoi1d(roi); }
            case 7  -> { ep.setPrice7d(px);  ep.setRoi7d(roi); }
            case 30 -> { ep.setPrice30d(px); ep.setRoi30d(roi); }
            default -> {}
        }
    }

    /**
     * Fetch daily candles from {@code exitAt+1d} out to {@code exitAt+lookaheadCalDays}
     * and return a map of {@code tradingDaysSinceExit -> close}. Handles market
     * holidays: consecutive non-trading calendar days simply don't appear in
     * Kite's response, so trading-day counting is naturally correct.
     */
    private Map<Integer, Double> fetchTradingDayCloses(long token, Instant exitAt,
                                                       int lookaheadCalDays) throws Throwable {
        Instant from = exitAt.plus(Duration.ofDays(1));
        Instant to   = Instant.now().isBefore(exitAt.plus(Duration.ofDays(lookaheadCalDays)))
                       ? Instant.now()
                       : exitAt.plus(Duration.ofDays(lookaheadCalDays));
        if (!to.isAfter(from)) return Map.of();

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        fmt.setTimeZone(TimeZone.getTimeZone(IST));
        HistoricalData hd = kite.getHistoricalData(
                Date.from(from), Date.from(to),
                String.valueOf(token), "day",
                false, false);
        if (hd == null || hd.dataArrayList == null || hd.dataArrayList.isEmpty()) return Map.of();

        Map<Integer, Double> out = new HashMap<>();
        int trading = 0;
        for (HistoricalData d : hd.dataArrayList) {
            trading++;
            if (trading == 1 || trading == 7 || trading == 30) {
                out.put(trading, d.close);
            }
            if (trading >= 30) break;
        }
        return out;
    }

    private static long daysSince(Instant t) {
        if (t == null) return 0;
        return Duration.between(t, Instant.now()).toDays();
    }
}

